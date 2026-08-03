from pathlib import Path

import geopandas as gpd
from shapely.geometry import LineString, MultiPoint, Point
from shapely.ops import snap, split
from shapely.strtree import STRtree


def endpoints(line):
    return [
        Point(line.coords[0]),
        Point(line.coords[-1]),
    ]

def extract_intersections(lines: gpd.GeoDataFrame) -> list[Point]:
    geometries = list(lines.geometry)

    tree = STRtree(geoms=geometries)

    points: list[Point] = []

    for i, geom in enumerate(geometries):
        # Find possible intersections using spatial index
        candidates = tree.query(geom)

        for j in candidates:
            # Avoid comparing the geometry with itself
            if i >= j:
                continue

            intersection = geom.intersection(geometries[j])

            if intersection.is_empty:
                continue

            if intersection.geom_type == "Point":
                points.append(intersection)

            elif intersection.geom_type == "MultiPoint":
                points.extend(intersection.geoms)

    return points

def split_edges(
    trails: gpd.GeoDataFrame,
    nodes: list[Point],
) -> gpd.GeoDataFrame:

    splitter = MultiPoint(nodes)

    edges = []

    for _, row in trails.iterrows():
        geom = snap(
            row.geometry,
            splitter,
            0.01,
        )

        result = split(
            geom,
            splitter,
        )

        for segment in result.geoms:
            if segment.length == 0:
                continue

            edge = row.copy()
            edge.geometry = segment

            edges.append(edge)

    return gpd.GeoDataFrame(
        edges,
        crs=trails.crs,
    )

def create_node_index(
    nodes: list[Point],
    precision: int = 2,
) -> tuple[list[Point], dict[tuple[float, float], int]]:
    unique: dict[tuple[float, float], Point] = {}

    for point in nodes:
        key = (
            round(point.x, precision),
            round(point.y, precision),
        )

        unique[key] = point

    node_list = list(unique.values())

    node_index: dict[tuple[float, float], int] = {
        (
            round(point.x, precision),
            round(point.y, precision),
        ): i
        for i, point in enumerate(node_list)
    }

    return node_list, node_index

def point_key(
    point: Point,
    precision: int = 2,
) -> tuple[float, float]:
    return (
        round(point.x, precision),
        round(point.y, precision),
    )

def attach_node_ids(
    edges: gpd.GeoDataFrame,
    node_index: dict[tuple[float, float], int],
) -> gpd.GeoDataFrame:

    edges = edges.copy()

    from_nodes = []
    to_nodes = []

    for geom in edges.geometry:
        start = Point(geom.coords[0])
        end = Point(geom.coords[-1])

        from_nodes.append(
            node_index[point_key(start)]
        )

        to_nodes.append(
            node_index[point_key(end)]
        )

    edges["from_node"] = from_nodes
    edges["to_node"] = to_nodes

    return edges

def main():
    INPUT_GDB = Path("./data/sample_wanderwege.gdb")
    LAYER = "TLM_STRASSE"

    trails: gpd.GeoDataFrame = gpd.read_file(
        INPUT_GDB,
        layer=LAYER,
    )

    nodes: list[Point] = []

    nodes.extend(extract_intersections(trails))

    trails: gpd.GeoDataFrame = (
        trails
        .reset_index()
        .rename(columns={"index": "feature_id"})
    ) # ty:ignore[invalid-assignment]

    trails: gpd.GeoDataFrame = trails.explode(
        index_parts=True
    ).reset_index(drop=True)  # ty:ignore[invalid-assignment]

    for geom in trails.geometry:
        nodes.extend(endpoints(geom))

    edges = split_edges(
        trails=trails,
        nodes=nodes,
    )

    node_points, node_index = create_node_index(
        nodes
    )

    print(
        f"Graph nodes: {len(node_points)}"
    )
    nodes_gdf = gpd.GeoDataFrame(
        {
            "id": range(len(node_points)),
        },
        geometry=node_points,
        crs=trails.crs,
    )
    print(nodes_gdf)

    nodes_gdf.to_file(
        Path("./data/sample_nodes.gdb"),
        layer=LAYER,
        driver="OpenFileGDB",
        engine="pyogrio",
    )


    edges_gdf = attach_node_ids(
        edges,
        node_index,
    )
    print(edges_gdf)

    edges_gdf.to_file(
        Path("./data/sample_edges.gdb"),
        layer=LAYER,
        driver="OpenFileGDB",
        engine="pyogrio",
    )
