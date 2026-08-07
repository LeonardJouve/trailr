import argparse
from pathlib import Path

import geopandas as gpd
from shapely.geometry import box


def main():
    parser = argparse.ArgumentParser(description="GDB trail sample")
    parser.add_argument("input_gdb", type=Path, help="Input GeoDatabase path")
    parser.add_argument("output_gdb", type=Path, help="Output GeoDatabase path")
    parser.add_argument("layer", type=str, help="Input layer name")
    parser.add_argument("minx", type=int, help="Bounding box minimum X")
    parser.add_argument("miny", type=int, help="Bounding box minimum Y")
    parser.add_argument("maxx", type=int, help="Bounding box maximum X")
    parser.add_argument("maxy", type=int, help="Bounding box maximum Y")
    args = parser.parse_args()
    input_gdb: Path = args.input_gdb
    output_gdb: Path = args.output_gdb
    layer: str = args.layer
    minx: int = args.minx
    miny: int = args.miny
    maxx: int = args.maxx
    maxy: int = args.maxy

    trails = gpd.read_file(
        input_gdb,
        layer=layer,
        engine="pyogrio",
    )

    bbox = gpd.GeoDataFrame(
        geometry=[
            box(
                minx,
                miny,
                maxx,
                maxy,
            )
        ],
        crs=trails.crs,
    )

    # Keep features intersecting the rectangle
    sample = gpd.sjoin(
        trails,
        bbox,
        predicate="intersects",
        how="inner",
    ).drop(columns=["index_right"])

    sample.to_file(
        output_gdb,
        layer=layer,
        driver="OpenFileGDB",
        engine="pyogrio",
    )
