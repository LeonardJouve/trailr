from pathlib import Path

import geopandas as gpd
from shapely.geometry import box


def main() -> None:
    INPUT_GDB = Path("./data/SWISSTLM3D_WANDERWEGE.gdb")
    OUTPUT_GDB = Path("./data/sample_wanderwege.gdb")
    LAYER = "TLM_STRASSE"

    print("Reading GDB...")

    trails = gpd.read_file(
        INPUT_GDB,
        layer=LAYER,
        engine="pyogrio",
    )

    print(f"Loaded {len(trails)} features")
    print(f"CRS: {trails.crs}")

    bbox = gpd.GeoDataFrame(
        geometry=[
            box(
                2549964,
                1129324,
                2557827,
                1139584,
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

    print(f"Selected {len(sample)} features")

    sample.to_file(
        OUTPUT_GDB,
        layer=LAYER,
        driver="OpenFileGDB",
        engine="pyogrio",
    )

    print(f"Saved {OUTPUT_GDB}")
