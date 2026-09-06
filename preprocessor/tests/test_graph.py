import unittest

import geopandas as gpd
from shapely.geometry import LineString

from preprocessor.graph import ensure_3d, validate_input


class GeometryNormalizationTest(unittest.TestCase):
    def _frame(self, geometries, **data) -> gpd.GeoDataFrame:
        return gpd.GeoDataFrame(
            data=data,
            geometry=geometries,
            crs="EPSG:2056",
        )

    def test_forces_two_dimensional_lines_to_z_zero(self):
        trails = self._frame(
            [
                LineString([(0, 0), (1, 1)]),
                LineString([(1, 1), (2, 2)]),
            ]
        )

        result = ensure_3d(trails)

        for geometry in result.geometry:
            self.assertTrue(geometry.has_z)
        self.assertEqual(
            list(result.geometry.iloc[0].coords),
            [(0.0, 0.0, 0.0), (1.0, 1.0, 0.0)],
        )

    def test_leaves_three_dimensional_lines_unchanged(self):
        trails = self._frame([LineString([(0, 0, 5), (1, 1, 5)])])

        result = ensure_3d(trails)

        self.assertEqual(
            list(result.geometry.iloc[0].coords),
            [(0.0, 0.0, 5.0), (1.0, 1.0, 5.0)],
        )

    def test_mixed_dimensions_keep_existing_z_and_fill_missing_z(self):
        trails = self._frame(
            [
                LineString([(0, 0, 5), (1, 1, 5)]),
                LineString([(2, 2), (3, 3)]),
            ]
        )

        result = ensure_3d(trails)

        self.assertEqual(
            list(result.geometry.iloc[0].coords),
            [(0.0, 0.0, 5.0), (1.0, 1.0, 5.0)],
        )
        self.assertEqual(
            list(result.geometry.iloc[1].coords),
            [(2.0, 2.0, 0.0), (3.0, 3.0, 0.0)],
        )

    def test_ensure_3d_passes_none_geometry_through(self):
        trails = self._frame([LineString([(0, 0, 5), (1, 1, 5)]), None])

        result = ensure_3d(trails)

        self.assertIsNone(result.geometry.iloc[1])

    def test_validate_input_rejects_none_geometry(self):
        trails = self._frame(
            [LineString([(0, 0, 5), (1, 1, 5)]), None],
            uuid=["a", "b"],
        )

        with self.assertRaises(ValueError):
            validate_input(ensure_3d(trails), {"uuid": "uuid:string"}, "ski")

    def test_validate_input_accepts_normalized_two_dimensional_lines(self):
        trails = self._frame([LineString([(0, 0), (1, 1)])], uuid=["a"])

        validate_input(ensure_3d(trails), {"uuid": "uuid:string"}, "ski")

    def test_validate_input_still_rejects_raw_two_dimensional_lines(self):
        trails = self._frame([LineString([(0, 0), (1, 1)])], uuid=["a"])

        with self.assertRaises(ValueError):
            validate_input(trails, {"uuid": "uuid:string"}, "ski")


if __name__ == "__main__":
    unittest.main()
