import hashlib
import http.server
import json
import tempfile
import threading
import unittest
from pathlib import Path

from preprocessor.contours import (
    Asset,
    annotate_contours,
    build_commands,
    cell_of,
    declared_length,
    discover_assets,
    download_asset,
    download_assets,
    fetch_bytes,
    load_manifest,
    minzoom_for,
    parse_args,
    replace_output,
    select_assets,
    sha256_from_multihash,
    write_manifest,
)

SHA_A = "a" * 64
SHA_B = "b" * 64


def asset(item_id: str) -> Asset:
    filename = f"{item_id}_2_2056_5728.tif"
    return Asset(item_id, filename, f"https://example.test/{filename}", SHA_A)


def stac_item(item_id: str, filename: str, digest: str) -> dict[str, object]:
    stem = filename.removesuffix("_2_2056_5728.tif")
    return {
        "id": item_id,
        "assets": {
            f"{stem}_0.5_2056_5728.tif": {
                "href": f"https://example.test/{stem}_0.5_2056_5728.tif",
                "type": "image/tiff; application=geotiff; profile=cloud-optimized",
                "gsd": 0.5,
                "proj:epsg": 2056,
                "file:checksum": f"1220{digest}",
            },
            filename: {
                "href": f"https://example.test/{filename}",
                "type": "image/tiff; application=geotiff; profile=cloud-optimized",
                "gsd": 2.0,
                "proj:epsg": 2056,
                "file:checksum": f"1220{digest}",
            },
        },
    }


class SelectionTest(unittest.TestCase):
    def test_keeps_one_newest_cog_per_cell(self):
        assets = [
            asset("swissalti3d_2019_2600-1200"),
            asset("swissalti3d_2025_2600-1200"),
            asset("swissalti3d_2021_2600-1200"),
            asset("swissalti3d_2019_2500-1100"),
        ]

        selected = select_assets(assets)

        self.assertEqual(
            [value.item_id for value in selected],
            ["swissalti3d_2019_2500-1100", "swissalti3d_2025_2600-1200"],
        )

    def test_selection_is_idempotent(self):
        assets = [
            asset("swissalti3d_2019_2600-1200"),
            asset("swissalti3d_2023_2600-1200"),
        ]

        self.assertEqual(select_assets(assets), select_assets(select_assets(assets)))

    def test_rejects_unparsable_item_id(self):
        with self.assertRaisesRegex(ValueError, "unexpected swissALTI3D item id"):
            select_assets([asset("swissalti3d_2019")])

    def test_reads_year_and_cell(self):
        self.assertEqual(cell_of("swissalti3d_2024_2679-1151"), (2024, "2679-1151"))


class CatalogTest(unittest.TestCase):
    def test_decodes_uppercase_sha256_multihash(self):
        self.assertEqual(sha256_from_multihash(f"1220{SHA_A.upper()}"), SHA_A)

    def test_paginates_and_selects_only_2m_lv95_cogs(self):
        pages: dict[str, dict[str, object]] = {
            "page-1": {
                "features": [
                    stac_item(
                        "swissalti3d_2025_2600-1200",
                        "swissalti3d_2025_2600-1200_2_2056_5728.tif",
                        SHA_B,
                    )
                ],
                "links": [{"rel": "next", "href": "page-2"}],
            },
            "page-2": {
                "features": [
                    stac_item(
                        "swissalti3d_2024_2500-1100",
                        "swissalti3d_2024_2500-1100_2_2056_5728.tif",
                        SHA_A,
                    )
                ],
                "links": [],
            },
        }

        assets = discover_assets("page-1", pages.__getitem__)

        self.assertEqual(
            assets,
            [
                Asset(
                    item_id="swissalti3d_2024_2500-1100",
                    filename="swissalti3d_2024_2500-1100_2_2056_5728.tif",
                    href=(
                        "https://example.test/"
                        "swissalti3d_2024_2500-1100_2_2056_5728.tif"
                    ),
                    sha256=SHA_A,
                ),
                Asset(
                    item_id="swissalti3d_2025_2600-1200",
                    filename="swissalti3d_2025_2600-1200_2_2056_5728.tif",
                    href=(
                        "https://example.test/"
                        "swissalti3d_2025_2600-1200_2_2056_5728.tif"
                    ),
                    sha256=SHA_B,
                ),
            ],
        )

    def test_rejects_pagination_cycle(self):
        page: dict[str, object] = {
            "features": [],
            "links": [{"rel": "next", "href": "page-1"}],
        }

        with self.assertRaisesRegex(ValueError, "pagination cycle"):
            discover_assets("page-1", lambda _url: page)

    def test_writes_stable_manifest_without_timestamp(self):
        asset = Asset("item-1", "tile.tif", "https://example.test/tile.tif", SHA_A)

        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "manifest.json"
            write_manifest([asset], path)
            manifest = json.loads(path.read_text(encoding="utf-8"))

        self.assertEqual(
            manifest,
            {
                "collection": "ch.swisstopo.swissalti3d",
                "resolution_m": 2,
                "epsg": 2056,
                "selection": "newest_per_cell",
                "assets": [
                    {
                        "item_id": "item-1",
                        "filename": "tile.tif",
                        "href": "https://example.test/tile.tif",
                        "sha256": SHA_A,
                    }
                ],
            },
        )


class ManifestReuseTest(unittest.TestCase):
    def test_round_trips_selected_assets(self):
        assets = select_assets(
            [
                asset("swissalti3d_2019_2600-1200"),
                asset("swissalti3d_2024_2600-1200"),
            ]
        )

        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "manifest.json"
            write_manifest(assets, path)

            self.assertEqual(load_manifest(path), assets)

    def test_reselects_legacy_manifest_with_every_campaign(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "manifest.json"
            path.write_text(
                json.dumps(
                    {
                        "collection": "ch.swisstopo.swissalti3d",
                        "resolution_m": 2,
                        "epsg": 2056,
                        "assets": [
                            {
                                "item_id": value.item_id,
                                "filename": value.filename,
                                "href": value.href,
                                "sha256": value.sha256,
                            }
                            for value in [
                                asset("swissalti3d_2019_2600-1200"),
                                asset("swissalti3d_2025_2600-1200"),
                            ]
                        ],
                    }
                ),
                encoding="utf-8",
            )

            self.assertIsNone(load_manifest(path))

    def test_rejects_missing_or_unreadable_manifest(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.assertIsNone(load_manifest(root / "absent.json"))
            broken = root / "manifest.json"
            broken.write_text("{not json", encoding="utf-8")
            self.assertIsNone(load_manifest(broken))


class NetworkTest(unittest.TestCase):
    """Guards the SHA-mismatch failure mode: a body that ends before its length."""

    def serve(self, handler: type[http.server.BaseHTTPRequestHandler]) -> str:
        server = http.server.HTTPServer(("127.0.0.1", 0), handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.shutdown)
        self.addCleanup(server.server_close)
        return f"http://127.0.0.1:{server.server_address[1]}/tile.tif"

    def test_short_body_is_reported_not_silently_truncated(self):
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                self.send_response(200)
                self.send_header("Content-Length", "100")
                self.end_headers()
                self.wfile.write(b"A" * 40)
                self.close_connection = True

            def log_message(self, *args: object) -> None:
                pass

        with self.assertRaisesRegex(ValueError, "incomplete|truncated"):
            fetch_bytes(self.serve(Handler))

    def test_full_body_is_returned(self):
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                body = b"B" * 64
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args: object) -> None:
                pass

        self.assertEqual(fetch_bytes(self.serve(Handler)), b"B" * 64)

    def test_connection_error_is_retried_then_published(self):
        payload = b"eventual-dem"
        digest = hashlib.sha256(payload).hexdigest()
        calls: list[int] = []

        def flaky(_url: str) -> bytes:
            calls.append(1)
            if len(calls) == 1:
                raise ConnectionResetError("connection reset by peer")
            return payload

        with tempfile.TemporaryDirectory() as tmp:
            target = download_asset(
                Asset("item", "tile.tif", "https://example.test/tile.tif", digest),
                Path(tmp),
                fetch=flaky,
                sleep=lambda _seconds: None,
            )

            self.assertEqual(target.read_bytes(), payload)
            self.assertEqual(len(calls), 2)

    def test_declared_length_parsing(self):
        self.assertEqual(declared_length("123"), 123)
        self.assertIsNone(declared_length(None))
        self.assertIsNone(declared_length("gzip"))
        self.assertIsNone(declared_length("-1"))


class DownloadTest(unittest.TestCase):
    def test_valid_cached_file_skips_network(self):
        payload = b"cached-dem"
        digest = hashlib.sha256(payload).hexdigest()
        asset = Asset("item", "tile.tif", "https://invalid.test/tile.tif", digest)

        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp)
            target = cache / "tile.tif"
            target.write_bytes(payload)

            result = download_asset(asset, cache)

        self.assertEqual(result, target)

    def test_corrupt_cached_file_is_atomically_replaced(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "source.tif"
            source.write_bytes(b"correct-dem")
            digest = hashlib.sha256(source.read_bytes()).hexdigest()
            asset = Asset("item", "tile.tif", source.as_uri(), digest)
            cache = root / "cache"
            cache.mkdir()
            (cache / "tile.tif").write_bytes(b"corrupt")

            result = download_asset(asset, cache)

            self.assertEqual(result.read_bytes(), b"correct-dem")
            self.assertFalse((cache / "tile.tif.part").exists())

    def test_checksum_failure_aborts_without_publishing_partial_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "source.tif"
            source.write_bytes(b"wrong-dem")
            asset = Asset("item", "tile.tif", source.as_uri(), SHA_A)
            cache = root / "cache"

            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                download_assets([asset], cache, workers=4, attempts=1)

            self.assertFalse((cache / "tile.tif").exists())
            self.assertFalse((cache / "tile.tif.part").exists())

    def test_transient_failure_is_retried_then_published(self):
        payload = b"good-dem"
        digest = hashlib.sha256(payload).hexdigest()
        attempts: list[int] = []

        def flaky(_url: str) -> bytes:
            attempts.append(len(attempts))
            if len(attempts) < 3:
                return b""  # truncated connection reset
            return payload

        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp) / "cache"
            target = download_asset(
                Asset("item", "tile.tif", "https://example.test/tile.tif", digest),
                cache,
                fetch=flaky,
                sleep=lambda _seconds: None,
            )

            self.assertEqual(target.read_bytes(), payload)
            self.assertEqual(len(attempts), 3)
            self.assertFalse((cache / "tile.tif.part").exists())

    def test_retries_exhausted_keeps_existing_tile_untouched(self):
        def broken(_url: str) -> bytes:
            return b"truncated"

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            cache = root / "cache"
            cache.mkdir()
            (cache / "tile.tif").write_bytes(b"previous-good")
            good = hashlib.sha256(b"expected-dem").hexdigest()
            slept: list[float] = []

            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                download_asset(
                    Asset("item", "tile.tif", "https://example.test/tile.tif", good),
                    cache,
                    fetch=broken,
                    sleep=slept.append,
                )

            self.assertEqual((cache / "tile.tif").read_bytes(), b"previous-good")
            self.assertFalse((cache / "tile.tif.part").exists())
            self.assertEqual(len(slept), 2)

    def test_rejects_nonpositive_attempt_count(self):
        with (
            tempfile.TemporaryDirectory() as tmp,
            self.assertRaisesRegex(ValueError, "attempts must be positive"),
        ):
            download_asset(
                Asset("item", "tile.tif", "https://example.test/tile.tif", SHA_A),
                Path(tmp),
                attempts=0,
            )

    def test_rejects_nonpositive_worker_count(self):
        with (
            tempfile.TemporaryDirectory() as tmp,
            self.assertRaisesRegex(ValueError, "workers must be positive"),
        ):
            download_assets([], Path(tmp), workers=0)


class AnnotationTest(unittest.TestCase):
    def test_assigns_required_minimum_zoom(self):
        expected = {
            100: 8,
            150: 11,
            20: 13,
            40: 13,
            10: 15,
            30: 15,
        }
        self.assertEqual(
            {elevation: minzoom_for(elevation) for elevation in expected},
            expected,
        )

    def test_keeps_only_integer_elevation_and_adds_tippecanoe_metadata(self):
        features = [
            {
                "type": "Feature",
                "properties": {"ID": 1, "elevation": 50.0},
                "geometry": {
                    "type": "LineString",
                    "coordinates": [[7.0, 46.0], [7.1, 46.1]],
                },
            },
            {
                "type": "Feature",
                "properties": {"ID": 2, "elevation": 20.0},
                "geometry": {
                    "type": "LineString",
                    "coordinates": [[7.2, 46.2], [7.3, 46.3]],
                },
            },
        ]

        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "raw.geojsonl"
            target = Path(tmp) / "contours.geojsonl"
            source.write_text(
                "\n".join(json.dumps(feature) for feature in features) + "\n",
                encoding="utf-8",
            )

            count = annotate_contours(source, target)
            actual = [json.loads(line) for line in target.read_text().splitlines()]

        self.assertEqual(count, 2)
        self.assertEqual(actual[0]["properties"], {"elevation": 50})
        self.assertEqual(actual[0]["tippecanoe"], {"minzoom": 11})
        self.assertEqual(actual[1]["properties"], {"elevation": 20})
        self.assertEqual(actual[1]["tippecanoe"], {"minzoom": 13})
        self.assertEqual(actual[0]["geometry"], features[0]["geometry"])

    def test_rejects_non_10m_elevation(self):
        feature = {
            "type": "Feature",
            "properties": {"elevation": 15.0},
            "geometry": {"type": "LineString", "coordinates": []},
        }

        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "raw.geojsonl"
            target = Path(tmp) / "contours.geojsonl"
            source.write_text(json.dumps(feature) + "\n", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "10 m multiple"):
                annotate_contours(source, target)


class BuildCommandTest(unittest.TestCase):
    def test_build_commands_preserve_elevation_and_required_zoom_range(self):
        work = Path("work")
        staging = Path("tiles.tmp")

        vrt, contour, tiles = build_commands(work, staging)

        self.assertEqual(
            vrt,
            [
                "gdalbuildvrt",
                "-strict",
                "-overwrite",
                "-input_file_list",
                str(work / "inputs.txt"),
                str(work / "dem.vrt"),
            ],
        )
        self.assertEqual(
            contour,
            [
                "gdal_contour",
                "-q",
                "-f",
                "GeoJSONSeq",
                "-lco",
                "RS=NO",
                "-a",
                "elevation",
                "-i",
                "10",
                "-nln",
                "contours",
                str(work / "dem.vrt"),
                str(work / "raw-contours.geojsonl"),
            ],
        )
        self.assertEqual(
            tiles,
            [
                "tippecanoe",
                "-P",
                "--force",
                "-e",
                str(staging),
                "-Z8",
                "-z15",
                "-l",
                "contours",
                "-y",
                "elevation",
                str(work / "contours.geojsonl"),
            ],
        )

    def test_cli_defaults_and_dem_override(self):
        options = parse_args(["build", "--dem", "tests/fixtures/dem.asc"])

        self.assertEqual(options.workers, 4)
        self.assertEqual(options.cache_dir, Path("data/swissalti3d"))
        self.assertEqual(options.work_dir, Path("data/contours-work"))
        self.assertEqual(options.output_dir, Path("data/tiles/contours"))
        self.assertEqual(options.dem, Path("tests/fixtures/dem.asc"))
        self.assertFalse(options.refresh_manifest)

    def test_cli_can_force_manifest_refresh(self):
        self.assertTrue(parse_args(["build", "--refresh-manifest"]).refresh_manifest)

    def test_replace_output_swaps_complete_tree(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            target = root / "contours"
            staging = root / "contours.tmp"
            target.mkdir()
            staging.mkdir()
            (target / "old").write_text("old", encoding="utf-8")
            (staging / "new").write_text("new", encoding="utf-8")

            replace_output(staging, target)

            self.assertFalse((target / "old").exists())
            self.assertEqual((target / "new").read_text(encoding="utf-8"), "new")
            self.assertFalse((root / "contours.old").exists())
