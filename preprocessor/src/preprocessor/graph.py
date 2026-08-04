from pathlib import Path

import geopandas as gpd
from shapely.geometry import LineString, MultiPoint, Point
from shapely.ops import snap, split
from shapely.strtree import STRtree


def edge_endpoints(edge: LineString) -> tuple[Point, Point]:
    return (
        Point(edge[0]),
        Point(edge[-1]),
    )

def point_key(point: Point, precision: int = 2) -> tuple[float, float]:
    return (
        round(point.x, precision),
        round(point.y, precision),
    )

def extract_intersections(trails: gpd.GeoDataFrame) -> list[Point]:
    geometries = list(trails.geometry)

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

def extract_endpoints(trails: gpd.GeoDataFrame) -> list[Point]:
    result: list[Point] = []

    for geom in trails.geometry:
        result.extend(list(edge_endpoints(geom.coords)))

    return result

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
) -> tuple[list[Point], dict[tuple[float, float], int]]:
    # TODO: use STRTree
    unique: dict[tuple[float, float], Point] = {}

    for point in nodes:
        key = point_key(point)

        unique[key] = point

    node_list = list(unique.values())

    node_index: dict[tuple[float, float], int] = {
        point_key(point): i
        for i, point in enumerate(node_list)
    }

    return node_list, node_index

def attach_node_ids(
    edges: gpd.GeoDataFrame,
    node_index: dict[tuple[float, float], int],
) -> gpd.GeoDataFrame:

    edges = edges.copy()

    from_nodes = []
    to_nodes = []

    for geom in edges.geometry:
        (start, end) = edge_endpoints(geom.coords)

        from_nodes.append(node_index[point_key(start)])

        to_nodes.append(node_index[point_key(end)])

    edges["from_node"] = from_nodes
    edges["to_node"] = to_nodes

    return edges

def main():
    INPUT_GDB = Path("./data/sample_wanderwege.gdb")
    OUTPUT_EDGES_GDB = Path("./data/sample_edges.gdb")
    OUTPUT_NODES_GDB = Path("./data/sample_nodes.gdb")
    LAYER = "TLM_STRASSE"

    trails: gpd.GeoDataFrame = gpd.read_file(INPUT_GDB, layer=LAYER)
    trails: gpd.GeoDataFrame = (
        trails
        .reset_index()
        .rename(columns={"index": "feature_id"})
    ) # ty:ignore[invalid-assignment]

    trails: gpd.GeoDataFrame = (
        trails
        .explode(index_parts=False)
        .reset_index(drop=True)
    ) # ty:ignore[invalid-assignment]

    nodes = (extract_intersections(trails) + extract_endpoints(trails))

    node_points, node_index = create_node_index(nodes)
    edges = split_edges(trails=trails, nodes=nodes)

    nodes_gdf = gpd.GeoDataFrame(
        data={"id": range(len(node_points))},
        geometry=node_points,
        crs=trails.crs,
    )
    nodes_gdf.to_file(
        OUTPUT_NODES_GDB,
        layer=LAYER,
        driver="OpenFileGDB",
        engine="pyogrio",
    )

    edges_gdf = attach_node_ids(edges, node_index)
    edges_gdf.to_file(
        OUTPUT_EDGES_GDB,
        layer=LAYER,
        driver="OpenFileGDB",
        engine="pyogrio",
    )
