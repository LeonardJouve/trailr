import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import geopandas as gpd
from shapely.geometry import LineString, MultiLineString

from preprocessor.drape import (
    clear_target,
    collect_unique_vertices,
    drape_frame,
    drape_geometry,
    fetch_batch,
    match_heights,
    plan_batches,
    validate_2d_lines,
    vertex_key,
    write_output,
)


def returned_point(x: float, y: float, alt: float) -> dict:
    return {"alts": {"COMB": alt}, "dist": 0.0, "easting": x, "northing": y}


class PlanBatchesTest(unittest.TestCase):
    def test_chunks_to_requested_size(self):
        points = [(float(i), 0.0) for i in range(9500)]

        batches = plan_batches(points, size=4000)

        self.assertEqual([len(batch) for batch in batches], [4000, 4000, 1500])

    def test_exact_multiple_has_no_remainder_batch(self):
        points = [(float(i), 0.0) for i in range(8000)]

        batches = plan_batches(points, size=4000)

        self.assertEqual([len(batch) for batch in batches], [4000, 4000])

    def test_empty_input_has_no_batches(self):
        self.assertEqual(plan_batches([], size=4000), [])

    def test_request_count_for_real_dataset_size(self):
        points = [(float(i), float(i)) for i in range(544769)]

        self.assertEqual(len(plan_batches(points)), 137)

    def test_batches_preserve_order_and_content(self):
        points = [(float(i), float(-i)) for i in range(10)]

        batches = plan_batches(points, size=3)

        self.assertEqual([point for batch in batches for point in batch], points)


class MatchHeightsTest(unittest.TestCase):
    def test_matches_echoed_points_with_millimetre_drift(self):
        sent = [(2600000.0, 1200000.0), (2600001.0, 1200001.0), (2600002.0, 1200002.0)]
        returned = [
            returned_point(2600000.0004, 1200000.0002, 2847.1),
            returned_point(2600000.5, 1200000.5, 2850.0),
            returned_point(2600000.9993, 1200001.0007, 2860.2),
            returned_point(2600002.0, 1200002.0, 2870.3),
        ]

        heights = match_heights(sent, returned, tol=0.01)

        self.assertEqual(
            heights,
            {
                (2600000.0, 1200000.0): 2847.1,
                (2600001.0, 1200001.0): 2860.2,
                (2600002.0, 1200002.0): 2870.3,
            },
        )

    def test_raises_when_sent_vertex_is_not_echoed(self):
        sent = [(2600000.0, 1200000.0), (2600001.0, 1200001.0)]
        returned = [returned_point(2600000.0, 1200000.0, 2847.1)]

        with self.assertRaises(ValueError) as ctx:
            match_heights(sent, returned, tol=0.01)

        self.assertIn("1", str(ctx.exception))

    def test_nearest_within_tolerance_wins(self):
        sent = [(2600000.0, 1200000.0)]
        returned = [
            returned_point(2600000.005, 1200000.0, 100.0),
            returned_point(2600000.0007, 1200000.0, 200.0),
        ]

        heights = match_heights(sent, returned, tol=0.01)

        self.assertEqual(heights, {vertex_key(2600000.0, 1200000.0): 200.0})


class CollectUniqueVerticesTest(unittest.TestCase):
    def test_deduplicates_across_parts_and_features(self):
        trails = gpd.GeoDataFrame(
            geometry=[
                LineString([(0, 0), (1, 1), (2, 2)]),
                MultiLineString(
                    [
                        [(2, 2), (3, 3)],
                        [(0, 0), (4, 4)],
                    ]
                ),
            ],
            crs="EPSG:2056",
        )

        points = collect_unique_vertices(trails)

        self.assertEqual(points, [(0.0, 0.0), (1.0, 1.0), (2.0, 2.0), (3.0, 3.0), (4.0, 4.0)])

    def test_near_duplicate_within_precision_collapses(self):
        trails = gpd.GeoDataFrame(
            geometry=[LineString([(0, 0), (0.0002, 0.0001)])],
            crs="EPSG:2056",
        )

        self.assertEqual(len(collect_unique_vertices(trails)), 1)


class Validate2dLinesTest(unittest.TestCase):
    def test_accepts_line_and_multiline_2d(self):
        trails = gpd.GeoDataFrame(
            geometry=[
                LineString([(0, 0), (1, 1)]),
                MultiLineString([[(0, 0), (1, 1)], [(2, 2), (3, 3)]]),
            ],
            crs="EPSG:2056",
        )

        validate_2d_lines(trails)

    def test_rejects_z_geometry(self):
        trails = gpd.GeoDataFrame(
            geometry=[LineString([(0, 0, 5), (1, 1, 5)])],
            crs="EPSG:2056",
        )

        with self.assertRaises(ValueError):
            validate_2d_lines(trails)

    def test_rejects_non_line_geometry(self):
        from shapely.geometry import Point

        trails = gpd.GeoDataFrame(
            geometry=[Point(0, 0)],
            crs="EPSG:2056",
        )

        with self.assertRaises(ValueError):
            validate_2d_lines(trails)

    def test_rejects_nan_geometry_entry_with_value_error(self):
        frame = SimpleNamespace(
            geometry=[LineString([(0, 0), (1, 1)]), float("nan")]
        )

        with self.assertRaises(ValueError):
            validate_2d_lines(frame)


class FetchBatchRetryTest(unittest.TestCase):
    def test_malformed_json_response_is_retried_then_raised(self):
        class StubResponse:
            def read(self):
                return b'{"alts": truncated'

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

        calls = []

        def stub_urlopen(request, timeout=None):
            calls.append(request)
            return StubResponse()

        with mock.patch(
            "preprocessor.drape.urllib.request.urlopen", stub_urlopen
        ), mock.patch("preprocessor.drape.time.sleep"):
            with self.assertRaises(RuntimeError):
                fetch_batch([(2600000.0, 1200000.0)])

        self.assertEqual(len(calls), 4)


class ClearTargetTest(unittest.TestCase):
    def test_removes_existing_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "out.gdb"
            (target / "sub").mkdir(parents=True)

            clear_target(target)

            self.assertFalse(target.exists())

    def test_removes_existing_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "out.gdb"
            target.write_bytes(b"not a gdb")

            clear_target(target)

            self.assertFalse(target.exists())

    def test_missing_target_is_noop(self):
        with tempfile.TemporaryDirectory() as tmp:
            clear_target(Path(tmp) / "absent.gdb")


class DrapeGeometryTest(unittest.TestCase):
    def test_line_string_gets_z(self):
        height_map = {
            vertex_key(0.0, 0.0): 1500.0,
            vertex_key(1.0, 1.0): 1600.5,
        }

        draped = drape_geometry(LineString([(0, 0), (1, 1)]), height_map)

        self.assertTrue(draped.has_z)
        self.assertEqual(
            list(draped.coords),
            [(0.0, 0.0, 1500.0), (1.0, 1.0, 1600.5)],
        )

    def test_multiline_preserves_parts_and_order(self):
        height_map = {
            vertex_key(x, y): 2000.0 + x
            for x, y in [(0, 0), (1, 1), (2, 2), (3, 3)]
        }
        geom = MultiLineString([[(0, 0), (1, 1)], [(2, 2), (3, 3)]])

        draped = drape_geometry(geom, height_map)

        self.assertEqual(draped.geom_type, "MultiLineString")
        self.assertEqual(len(draped.geoms), 2)
        self.assertEqual(
            [list(part.coords) for part in draped.geoms],
            [
                [(0.0, 0.0, 2000.0), (1.0, 1.0, 2001.0)],
                [(2.0, 2.0, 2002.0), (3.0, 3.0, 2003.0)],
            ],
        )


class DrapeFrameTest(unittest.TestCase):
    def test_preserves_attributes_and_crs(self):
        trails = gpd.GeoDataFrame(
            data={"segm_id": ["a", "b"], "access": [1, 2]},
            geometry=[
                LineString([(0, 0), (1, 1)]),
                MultiLineString([[(2, 2), (3, 3)]]),
            ],
            crs="EPSG:2056",
        )
        height_map = {
            vertex_key(x, y): 3000.0 for x, y in [(0, 0), (1, 1), (2, 2), (3, 3)]
        }

        draped = drape_frame(trails, height_map)

        self.assertEqual(list(draped.columns), ["segm_id", "access", "geometry"])
        self.assertEqual(list(draped["segm_id"]), ["a", "b"])
        self.assertEqual(draped.crs.to_epsg(), 2056)
        self.assertTrue(all(geometry.has_z for geometry in draped.geometry))
        self.assertEqual(draped.geometry.iloc[1].geom_type, "MultiLineString")


class WriteOutputTest(unittest.TestCase):
    def test_int64_columns_survive_gdb_roundtrip(self):
        # Regression: without TARGET_ARCGIS_VERSION the OpenFileGDB driver
        # downgrades Integer64 fields to Double, and the graph CSVs then
        # write "0.0" for `access:int`, failing the Neo4j import.
        frame = gpd.GeoDataFrame(
            {
                "segm_id": [1, 2, 3],
                "access": [0, 1, 0],
                "geometry": [
                    LineString([(2573311.949, 1080362.466, 2661.0), (2573312.0, 1080362.0, 2662.0)]),
                    LineString([(2573313.0, 1080363.0, 2663.0), (2573314.0, 1080364.0, 2664.0)]),
                    LineString([(2573315.0, 1080365.0, 2665.0), (2573316.0, 1080366.0, 2666.0)]),
                ],
            },
            crs="EPSG:2056",
        )

        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "roundtrip.gdb"
            write_output(frame, output, "lines")
            back = gpd.read_file(output, layer="lines", engine="pyogrio")

        self.assertEqual(back["segm_id"].dtype.kind, "i")
        self.assertEqual(back["access"].dtype.kind, "i")
        self.assertEqual(list(back["segm_id"]), [1, 2, 3])
        self.assertEqual(list(back["access"]), [0, 1, 0])


if __name__ == "__main__":
    unittest.main()
