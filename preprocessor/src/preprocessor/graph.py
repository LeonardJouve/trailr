import argparse
import json
from pathlib import Path

import geopandas as gpd
from shapely.geometry import LineString, MultiPoint, Point
from shapely.ops import snap, split
from shapely.strtree import STRtree

PointKey = tuple[float, float, float]

def edge_endpoints(edge: LineString) -> tuple[Point, Point]:
    return (
        Point(edge[0]),
        Point(edge[-1]),
    )

def point_key(point: Point) -> PointKey:
    precision = 2
    return (
        round(point.x, precision),
        round(point.y, precision),
        round(point.z, precision),
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
        geom = snap(row.geometry, splitter, 0.01)

        result = split(geom, splitter)

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
) -> tuple[list[Point], dict[PointKey, int]]:
    node_points: list[Point] = []
    node_index: dict[PointKey, int] = {}

    for point in nodes:
        key = point_key(point)
        if key in node_index:
            continue

        node_index[key] = len(node_points)
        node_points.append(point)

    return node_points, node_index

def attach_node_ids(
    edges: gpd.GeoDataFrame,
    node_index: dict[PointKey, int],
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

    int_columns = edges.select_dtypes(include=["int64"]).columns
    edges[int_columns] = edges[int_columns].astype("int32")

    return edges

def main():
    parser = argparse.ArgumentParser(description="GDB trail preprocess")
    parser.add_argument("input_gdb", type=Path, help="Input GeoDatabase path")
    parser.add_argument("output_folder", type=Path, help="Output folder")
    parser.add_argument("layer", type=str, help="Input layer name")
    args = parser.parse_args()
    input_gdb: Path = args.input_gdb
    output_folder: Path = args.output_folder
    layer: str = args.layer

    gdb_file = input_gdb.stem
    output_edges_gdb = output_folder / f"{gdb_file}_edges.gdb"
    output_edges_csv = output_folder / f"{gdb_file}_edges.csv"
    output_nodes_gdb = output_folder / f"{gdb_file}_nodes.gdb"
    output_nodes_csv = output_folder / f"{gdb_file}_nodes.csv"

    trails: gpd.GeoDataFrame = gpd.read_file(input_gdb, layer=layer)
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
    nodes_gdf["id"] = nodes_gdf["id"].astype("int32")
    nodes_gdf.to_file(
        output_nodes_gdb,
        layer=layer,
        driver="OpenFileGDB",
        engine="pyogrio",
    )
    node_attributes = [
        "id",
        "x",
        "y",
        "z",
    ]
    nodes_gdf["x"] = nodes_gdf.geometry.x
    nodes_gdf["y"] = nodes_gdf.geometry.y
    nodes_gdf["z"] = nodes_gdf.geometry.z
    nodes_gdf[node_attributes].to_csv(output_nodes_csv, index=False)

    edges_gdf = attach_node_ids(edges, node_index)
    field_mapping = {
        "UUID": "uuid",
        "WANDERWEGE": "trail_type",
        "STUFE": "difficulty",
        "BEFAHRBARKEIT": "accessibility",
        "VERKEHRSBESCHRAENKUNG": "traffic_restriction",
        "BELAGSART": "surface_type",
        "KUNSTBAUTE": "structure_type",
    }
    edges_gdf = edges_gdf.rename(columns=field_mapping)
    edges_gdf.to_file(
        output_edges_gdb,
        layer=layer,
        driver="OpenFileGDB",
        engine="pyogrio",
    )
    edge_attributes = [
        "uuid",
        "trail_type",
        "difficulty",
        "accessibility",
        "traffic_restriction",
        "surface_type",
        "structure_type",
        "from_node",
        "to_node",
        "length",
        "coords",
    ]
    edges_gdf["length"] = edges.geometry.length
    edges_gdf["coords"] = edges_gdf.geometry.apply(
        lambda geom: json.dumps(list(map(list, geom.coords)))
    )
    edges_gdf[edge_attributes].to_csv(output_edges_csv, index=False)
