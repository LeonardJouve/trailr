import argparse
import hashlib
import json
import logging
import shutil
import subprocess
import urllib.request
from collections.abc import Callable, Sequence
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import cast

COLLECTION_ID = "ch.swisstopo.swissalti3d"
ITEMS_URL = (
    "https://data.geo.admin.ch/api/stac/v1/collections/"
    "ch.swisstopo.swissalti3d/items?limit=100"
)
REQUEST_TIMEOUT = 120
COG_MEDIA_TYPE = "image/tiff; application=geotiff; profile=cloud-optimized"

JsonObject = dict[str, object]
JsonGetter = Callable[[str], JsonObject]
logger = logging.getLogger("contours")


@dataclass(frozen=True, order=True)
class Asset:
    item_id: str
    filename: str
    href: str
    sha256: str


def sha256_from_multihash(value: str) -> str:
    normalized = value.lower()
    if len(normalized) != 68 or not normalized.startswith("1220"):
        raise ValueError(f"expected sha2-256 multihash, got {value!r}")
    try:
        bytes.fromhex(normalized)
    except ValueError as error:
        raise ValueError(f"invalid multihash hex: {value!r}") from error
    return normalized[4:]


def fetch_json(url: str) -> JsonObject:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "trailr-contours/1"},
    )
    with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT) as response:
        value = json.loads(response.read().decode("utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"STAC response is not an object: {url}")
    return cast(JsonObject, value)


def asset_from_item(item: JsonObject) -> Asset:
    item_id = item.get("id")
    raw_assets = item.get("assets")
    if not isinstance(item_id, str) or not isinstance(raw_assets, dict):
        raise ValueError("STAC item needs string id and object assets")

    matches: list[Asset] = []
    for name, raw_asset in raw_assets.items():
        if not isinstance(name, str) or not isinstance(raw_asset, dict):
            continue
        asset = cast(JsonObject, raw_asset)
        if (
            asset.get("type") != COG_MEDIA_TYPE
            or asset.get("gsd") != 2.0
            or asset.get("proj:epsg") != 2056
            or Path(name).name != name
            or not name.endswith("_2_2056_5728.tif")
        ):
            continue
        href = asset.get("href")
        checksum = asset.get("file:checksum")
        if not isinstance(href, str) or not isinstance(checksum, str):
            raise ValueError(f"2 m COG in {item_id} lacks href/checksum")
        matches.append(Asset(item_id, name, href, sha256_from_multihash(checksum)))

    if len(matches) != 1:
        raise ValueError(
            f"expected one 2 m LV95 COG in {item_id}, found {len(matches)}"
        )
    return matches[0]


def discover_assets(items_url: str, get_json: JsonGetter = fetch_json) -> list[Asset]:
    assets: list[Asset] = []
    seen_pages: set[str] = set()
    url: str | None = items_url

    while url is not None:
        if url in seen_pages:
            raise ValueError(f"STAC pagination cycle at {url}")
        seen_pages.add(url)
        page = get_json(url)
        features = page.get("features")
        links = page.get("links")
        if not isinstance(features, list) or not isinstance(links, list):
            raise ValueError(f"invalid STAC FeatureCollection at {url}")
        for feature in features:
            if not isinstance(feature, dict):
                raise ValueError(f"invalid STAC feature at {url}")
            assets.append(asset_from_item(cast(JsonObject, feature)))

        next_urls = [
            link.get("href")
            for link in links
            if isinstance(link, dict) and link.get("rel") == "next"
        ]
        if len(next_urls) > 1 or any(not isinstance(value, str) for value in next_urls):
            raise ValueError(f"invalid STAC next link at {url}")
        url = cast(str, next_urls[0]) if next_urls else None

    assets.sort(key=lambda asset: asset.filename)
    if not assets:
        raise ValueError("STAC returned no 2 m LV95 COGs")
    if len({asset.filename for asset in assets}) != len(assets):
        raise ValueError("STAC returned duplicate COG filenames")
    return assets


def write_manifest(assets: list[Asset], path: Path) -> None:
    manifest = {
        "collection": COLLECTION_ID,
        "resolution_m": 2,
        "epsg": 2056,
        "assets": [asdict(asset) for asset in assets],
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_asset(asset: Asset, cache_dir: Path) -> Path:
    cache_dir.mkdir(parents=True, exist_ok=True)
    target = cache_dir / asset.filename
    if target.is_file() and file_sha256(target) == asset.sha256:
        logger.info("cached %s", asset.filename)
        return target

    partial = target.with_suffix(target.suffix + ".part")
    partial.unlink(missing_ok=True)
    request = urllib.request.Request(
        asset.href,
        headers={"User-Agent": "trailr-contours/1"},
    )
    try:
        with (
            urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT) as response,
            partial.open("wb") as output,
        ):
            shutil.copyfileobj(response, output, length=1024 * 1024)
        actual = file_sha256(partial)
        if actual != asset.sha256:
            raise ValueError(
                f"checksum mismatch for {asset.filename}: "
                f"expected {asset.sha256}, got {actual}"
            )
        partial.replace(target)
    except BaseException:
        partial.unlink(missing_ok=True)
        raise
    return target


def download_assets(assets: list[Asset], cache_dir: Path, workers: int) -> list[Path]:
    if workers < 1:
        raise ValueError("workers must be positive")

    results: list[Path] = []
    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = [
            executor.submit(download_asset, asset, cache_dir) for asset in assets
        ]
        try:
            for future in as_completed(futures):
                results.append(future.result())
        except BaseException:
            for future in futures:
                future.cancel()
            raise
    return sorted(results)


def minzoom_for(elevation: int) -> int:
    if elevation % 100 == 0:
        return 8
    if elevation % 50 == 0:
        return 11
    if elevation % 20 == 0:
        return 13
    return 15


def annotate_contours(source: Path, target: Path) -> int:
    count = 0
    target.parent.mkdir(parents=True, exist_ok=True)
    with (
        source.open(encoding="utf-8") as input_file,
        target.open("w", encoding="utf-8", newline="\n") as output_file,
    ):
        for line_number, line in enumerate(input_file, start=1):
            if not line.strip():
                continue
            feature = json.loads(line)
            if not isinstance(feature, dict):
                raise ValueError(f"feature {line_number} is not an object")
            properties = feature.get("properties")
            if not isinstance(properties, dict):
                raise ValueError(f"feature {line_number} lacks properties")
            raw_elevation = properties.get("elevation")
            if not isinstance(raw_elevation, int | float):
                raise ValueError(f"feature {line_number} lacks numeric elevation")
            elevation = round(raw_elevation)
            if abs(raw_elevation - elevation) > 1e-6 or elevation % 10 != 0:
                raise ValueError(
                    f"feature {line_number} elevation is not a 10 m multiple"
                )

            feature["properties"] = {"elevation": elevation}
            feature["tippecanoe"] = {"minzoom": minzoom_for(elevation)}
            output_file.write(json.dumps(feature, separators=(",", ":")) + "\n")
            count += 1

    if count == 0:
        raise ValueError("GDAL produced no contours")
    return count


@dataclass(frozen=True)
class BuildOptions:
    cache_dir: Path
    work_dir: Path
    output_dir: Path
    workers: int
    dem: Path | None
    stac_url: str


def parse_args(argv: Sequence[str] | None = None) -> BuildOptions:
    parser = argparse.ArgumentParser(description="Build swissALTI3D contour tiles")
    commands = parser.add_subparsers(dest="command", required=True)
    build_parser = commands.add_parser("build")
    build_parser.add_argument(
        "--cache-dir", type=Path, default=Path("data/swissalti3d")
    )
    build_parser.add_argument(
        "--work-dir", type=Path, default=Path("data/contours-work")
    )
    build_parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("data/tiles/contours"),
    )
    build_parser.add_argument("--workers", type=int, default=4)
    build_parser.add_argument("--dem", type=Path)
    build_parser.add_argument("--stac-url", default=ITEMS_URL)
    namespace = parser.parse_args(argv)
    return BuildOptions(
        cache_dir=cast(Path, namespace.cache_dir),
        work_dir=cast(Path, namespace.work_dir),
        output_dir=cast(Path, namespace.output_dir),
        workers=cast(int, namespace.workers),
        dem=cast(Path | None, namespace.dem),
        stac_url=cast(str, namespace.stac_url),
    )


def build_commands(
    work_dir: Path,
    staging_dir: Path,
) -> tuple[list[str], list[str], list[str]]:
    vrt = [
        "gdalbuildvrt",
        "-strict",
        "-overwrite",
        "-input_file_list",
        str(work_dir / "inputs.txt"),
        str(work_dir / "dem.vrt"),
    ]
    contour = [
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
        str(work_dir / "dem.vrt"),
        str(work_dir / "raw-contours.geojsonl"),
    ]
    tiles = [
        "tippecanoe",
        "-P",
        "--force",
        "-e",
        str(staging_dir),
        "-Z8",
        "-z15",
        "-l",
        "contours",
        "-y",
        "elevation",
        str(work_dir / "contours.geojsonl"),
    ]
    return vrt, contour, tiles


def replace_output(staging: Path, target: Path) -> None:
    backup = target.with_name(target.name + ".old")
    if backup.exists():
        shutil.rmtree(backup)
    if target.exists():
        target.replace(backup)
    try:
        staging.replace(target)
    except BaseException:
        if backup.exists():
            backup.replace(target)
        raise
    if backup.exists():
        shutil.rmtree(backup)


def run_checked(command: list[str]) -> None:
    logger.info("running %s", " ".join(command))
    subprocess.run(command, check=True)


def require_tools() -> None:
    missing = [
        command
        for command in ("gdalbuildvrt", "gdal_contour", "tippecanoe")
        if shutil.which(command) is None
    ]
    if missing:
        raise RuntimeError(f"missing required commands: {', '.join(missing)}")


def build(options: BuildOptions) -> None:
    if options.workers < 1:
        raise ValueError("workers must be positive")
    require_tools()

    if options.work_dir.exists():
        shutil.rmtree(options.work_dir)
    options.work_dir.mkdir(parents=True)
    options.output_dir.parent.mkdir(parents=True, exist_ok=True)

    if options.dem is None:
        assets = discover_assets(options.stac_url)
        write_manifest(assets, options.cache_dir / "manifest.json")
        rasters = download_assets(assets, options.cache_dir, options.workers)
    else:
        if not options.dem.is_file():
            raise ValueError(f"DEM does not exist: {options.dem}")
        rasters = [options.dem]

    input_list = options.work_dir / "inputs.txt"
    input_list.write_text(
        "".join(f"{path.resolve()}\n" for path in rasters),
        encoding="utf-8",
    )

    staging = options.output_dir.with_name(options.output_dir.name + ".tmp")
    if staging.exists():
        shutil.rmtree(staging)
    vrt_command, contour_command, tiles_command = build_commands(
        options.work_dir,
        staging,
    )
    run_checked(vrt_command)
    run_checked(contour_command)
    annotate_contours(
        options.work_dir / "raw-contours.geojsonl",
        options.work_dir / "contours.geojsonl",
    )
    run_checked(tiles_command)
    replace_output(staging, options.output_dir)
    shutil.rmtree(options.work_dir)


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-8s %(name)s %(message)s",
        datefmt="%H:%M:%S",
    )
    build(parse_args())


if __name__ == "__main__":
    main()
