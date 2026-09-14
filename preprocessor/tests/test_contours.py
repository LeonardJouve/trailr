import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from preprocessor.contours import (
    Asset,
    discover_assets,
    download_asset,
    download_assets,
    sha256_from_multihash,
    write_manifest,
)


SHA_A = "a" * 64
SHA_B = "b" * 64


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
                download_assets([asset], cache, workers=4)

            self.assertFalse((cache / "tile.tif").exists())
            self.assertFalse((cache / "tile.tif.part").exists())

    def test_rejects_nonpositive_worker_count(self):
        with (
            tempfile.TemporaryDirectory() as tmp,
            self.assertRaisesRegex(ValueError, "workers must be positive"),
        ):
            download_assets([], Path(tmp), workers=0)
