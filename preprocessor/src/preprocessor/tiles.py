import argparse
import logging
from pathlib import Path

import geopandas as gpd

logger = logging.getLogger("tiles")


def main():
    parser = argparse.ArgumentParser(
        description="Export a GDB layer to WGS84 GeoJSON for vector tile generation"
    )
    parser.add_argument("dataset", type=Path, help="input GeoDatabase path")
    parser.add_argument("layer", type=str, help="layer name")
    parser.add_argument("output", type=Path, help="output GeoJSON path")
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
    trails = trails[["geometry"]].to_crs("EPSG:4326")
    logger.info("exporting %d features from layer %s", len(trails), args.layer)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    trails.to_file(args.output, driver="GeoJSON", engine="pyogrio")
    logger.info("wrote %s", args.output)
