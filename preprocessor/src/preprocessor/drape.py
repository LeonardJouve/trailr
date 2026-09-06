import argparse
import json
import logging
import math
import shutil
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from pathlib import Path

import geopandas as gpd
from shapely.geometry import LineString, MultiLineString

PROFILE_URL = "https://api3.geo.admin.ch/rest/services/profile.json"
KEY_PRECISION = 3
MATCH_TOLERANCE = 0.01
BATCH_SIZE = 4000
REQUEST_TIMEOUT = 60
MAX_RETRIES = 3
BATCH_SLEEP = 0.4

logger = logging.getLogger("drape")


def vertex_key(x: float, y: float) -> tuple[float, float]:
    return (round(x, KEY_PRECISION), round(y, KEY_PRECISION))


def validate_2d_lines(trails: gpd.GeoDataFrame) -> None:
    if any(
        geometry is None
        or geometry.geom_type not in {"LineString", "MultiLineString"}
        or geometry.has_z
        for geometry in trails.geometry
    ):
        raise ValueError("layer must contain only 2D line geometry")


def collect_unique_vertices(trails: gpd.GeoDataFrame) -> list[tuple[float, float]]:
    unique: dict[tuple[float, float], tuple[float, float]] = {}

    for geometry in trails.geometry:
        parts = geometry.geoms if geometry.geom_type == "MultiLineString" else [geometry]

        for part in parts:
            for x, y in part.coords:
                unique.setdefault(vertex_key(x, y), (x, y))

    return list(unique.values())


def plan_batches(
    points: list[tuple[float, float]],
    size: int = BATCH_SIZE,
) -> list[list[tuple[float, float]]]:
    return [points[i : i + size] for i in range(0, len(points), size)]


def match_heights(
    sent: list[tuple[float, float]],
    returned: list[dict],
    tol: float = MATCH_TOLERANCE,
) -> dict[tuple[float, float], float]:
    grid: dict[tuple[int, int], list[tuple[float, float, float]]] = defaultdict(list)

    for point in returned:
        easting = float(point["easting"])
        northing = float(point["northing"])
        altitude = float(point["alts"]["COMB"])
        grid[(math.floor(easting / tol), math.floor(northing / tol))].append(
            (easting, northing, altitude)
        )

    heights: dict[tuple[float, float], float] = {}
    misses: list[tuple[float, float]] = []

    for x, y in sent:
        cell_x = math.floor(x / tol)
        cell_y = math.floor(y / tol)

        best_altitude = None
        best_distance_sq = tol * tol

        for gx in (cell_x - 1, cell_x, cell_x + 1):
            for gy in (cell_y - 1, cell_y, cell_y + 1):
                for easting, northing, altitude in grid.get((gx, gy), ()):
                    distance_sq = (easting - x) ** 2 + (northing - y) ** 2
                    if distance_sq <= best_distance_sq:
                        best_distance_sq = distance_sq
                        best_altitude = altitude

        if best_altitude is None:
            misses.append((x, y))
        else:
            heights[vertex_key(x, y)] = best_altitude

    if misses:
        raise ValueError(
            f"{len(misses)} sent vertices were not echoed by the profile service "
            f"(first few: {misses[:5]})"
        )

    return heights


def fetch_batch(batch: list[tuple[float, float]]) -> list[dict]:
    geom = json.dumps(
        {"type": "LineString", "coordinates": [[x, y] for x, y in batch]}
    )
    payload = urllib.parse.urlencode(
        {
            "geom": geom,
            "sr": "2056",
            "nb_points": "50",
            "distinct_points": "true",
        }
    ).encode("utf-8")

    for attempt in range(MAX_RETRIES + 1):
        try:
            request = urllib.request.Request(
                PROFILE_URL,
                data=payload,
                headers={"User-Agent": "trailr-preprocessor-drape"},
            )
            with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT) as response:
                return json.loads(response.read().decode("utf-8"))
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            if attempt == MAX_RETRIES:
                raise RuntimeError(
                    f"profile request failed after {MAX_RETRIES + 1} attempts: {error}"
                ) from error
            backoff = 2**attempt
            logger.warning("profile request failed (%s), retrying in %d s", error, backoff)
            time.sleep(backoff)

    raise AssertionError("unreachable")


def drape_geometry(
    geometry: LineString | MultiLineString,
    height_map: dict[tuple[float, float], float],
) -> LineString | MultiLineString:
    def drape_part(part: LineString) -> LineString:
        return LineString(
            [(x, y, height_map[vertex_key(x, y)]) for x, y in part.coords]
        )

    if geometry.geom_type == "LineString":
        return drape_part(geometry)

    return MultiLineString([drape_part(part) for part in geometry.geoms])


def drape_frame(
    trails: gpd.GeoDataFrame,
    height_map: dict[tuple[float, float], float],
) -> gpd.GeoDataFrame:
    draped = trails.copy()
    draped["geometry"] = [drape_geometry(geometry, height_map) for geometry in trails.geometry]
    return draped


def main():
    parser = argparse.ArgumentParser(
        description="Drape a 2D line layer with swissALTI3D elevations via the swisstopo profile API"
    )
    parser.add_argument("dataset", type=Path, help="input dataset path (e.g. a GPKG)")
    parser.add_argument("layer", type=str, help="layer name")
    parser.add_argument("output", type=Path, help="output FileGDB path")
    args = parser.parse_args()

    if not args.dataset.exists():
        raise ValueError(f"dataset does not exist: {args.dataset}")

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-8s %(name)s %(message)s",
        datefmt="%H:%M:%S",
    )

    trails: gpd.GeoDataFrame = gpd.read_file(
        args.dataset,
        layer=args.layer,
        engine="pyogrio",
    )
    logger.info("loaded %d features from layer %s", len(trails), args.layer)

    validate_2d_lines(trails)

    if trails.crs is None or trails.crs.to_epsg() != 2056:
        raise ValueError(f"layer must be in EPSG:2056, got {trails.crs}")

    points = collect_unique_vertices(trails)
    batches = plan_batches(points)
    logger.info("fetching elevations for %d unique vertices in %d requests", len(points), len(batches))

    start = time.perf_counter()
    heights: dict[tuple[float, float], float] = {}

    for i, batch in enumerate(batches, start=1):
        returned = fetch_batch(batch)
        heights.update(match_heights(batch, returned, tol=MATCH_TOLERANCE))
        logger.info("batch %d/%d done (%.1f s)", i, len(batches), time.perf_counter() - start)
        if i < len(batches):
            time.sleep(BATCH_SLEEP)

    draped = drape_frame(trails, heights)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.exists():
        shutil.rmtree(args.output)
    draped.to_file(
        args.output,
        layer=args.layer,
        driver="OpenFileGDB",
        engine="pyogrio",
    )

    elevations = list(heights.values())
    logger.info(
        "wrote %s: %d features, %d vertices, elevation %.1f-%.1f m",
        args.output,
        len(draped),
        len(heights),
        min(elevations),
        max(elevations),
    )
