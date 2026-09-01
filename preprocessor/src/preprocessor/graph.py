import argparse
import json
import logging
import time
from pathlib import Path

import geopandas as gpd
from shapely.geometry import LineString, MultiPoint, Point
from shapely.ops import snap, split
from shapely.strtree import STRtree

PointKey = tuple[float, float, float]

logger = logging.getLogger("graph")

def log_progress(step: str, i: int, total: int, start: float) -> None:
    period = max(1, total // 100)
    if i % period == 0 or i == total:
        logger.info(
            "%s %d/%d (%.1f s)",
            step,
            i,
            total,
            time.perf_counter() - start,
        )

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

    total = len(geometries)
    start = time.perf_counter()

    for i, geom in enumerate(geometries, start=1):
        log_progress("intersections", i, total, start)

        # Find possible intersections using spatial index
        candidates = tree.query(geom)

        for j in candidates:
            # Avoid comparing the geometry with itself
            if i - 1 >= j:
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

def split_at_points(
    geom: LineString,
    points: list[Point],
    tolerance: float = 0.01,
) -> list[LineString]:
    if not points:
        return [geom]

    splitter = MultiPoint(points)
    snapped = snap(geom, splitter, tolerance)

    segments: list[LineString] = []

    for segment in split(snapped, splitter).geoms:
        if segment.length > 0:
            segments.append(segment)

    return segments

def split_edges(
    trails: gpd.GeoDataFrame,
    nodes: list[Point],
) -> gpd.GeoDataFrame:
    node_tree = STRtree(nodes)

    geometries = trails.geometry.to_numpy()

    segment_indices: list[int] = []
    segments: list[LineString] = []

    total = len(geometries)
    start = time.perf_counter()

    for i, geom in enumerate(geometries, start=1):
        log_progress("split_edges", i, total, start)

        nearby = [nodes[j] for j in node_tree.query(geom)]

        for segment in split_at_points(geom, nearby):
            segment_indices.append(i - 1)
            segments.append(segment)

    edges = trails.loc[segment_indices].reset_index(drop=True)
    edges["geometry"] = segments

    return edges

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

    total = len(edges)
    start = time.perf_counter()

    for i, geom in enumerate(edges.geometry, start=1):
        log_progress("node_ids", i, total, start)

        (start_point, end_point) = edge_endpoints(geom.coords)

        from_nodes.append(node_index[point_key(start_point)])

        to_nodes.append(node_index[point_key(end_point)])

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

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-8s %(name)s %(message)s",
        datefmt="%H:%M:%S",
    )

    gdb_file = input_gdb.stem
    output_edges_gdb = output_folder / f"{gdb_file}_edges.gdb"
    output_edges_csv = output_folder / f"{gdb_file}_edges.csv"
    output_nodes_gdb = output_folder / f"{gdb_file}_nodes.gdb"
    output_nodes_csv = output_folder / f"{gdb_file}_nodes.csv"

    pipeline_start = time.perf_counter()

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
    logger.info("loaded %d features", len(trails))

    nodes = (extract_intersections(trails) + extract_endpoints(trails))

    node_points, node_index = create_node_index(nodes)
    logger.info("built %d nodes from %d points", len(node_points), len(nodes))

    edges = split_edges(trails=trails, nodes=nodes)
    logger.info("built %d edges", len(edges))

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
    nodes_field_mapping = {
        "id": "id:ID",
        "x": "x:double",
        "y": "y:double",
        "z": "z:double",
    }
    nodes_gdf = nodes_gdf.rename(columns=nodes_field_mapping)
    node_attributes = [
        "id:ID",
        "x:double",
        "y:double",
        "z:double",
    ]
    nodes_gdf["x:double"] = nodes_gdf.geometry.x
    nodes_gdf["y:double"] = nodes_gdf.geometry.y
    nodes_gdf["z:double"] = nodes_gdf.geometry.z
    nodes_gdf[node_attributes].to_csv(output_nodes_csv, index=False)

    edges_gdf = attach_node_ids(edges, node_index)
    edges_gdf.to_file(
        output_edges_gdb,
        layer=layer,
        driver="OpenFileGDB",
        engine="pyogrio",
    )
    edges_field_mapping = {
        "UUID": "uuid:string",
        "WANDERWEGE": "trail_type:int",
        "STUFE": "floor:int",
        "BEFAHRBARKEIT": "accessibility:int",
        "VERKEHRSBESCHRAENKUNG": "traffic_restriction:int",
        "BELAGSART": "surface_type:int",
        "KUNSTBAUTE": "structure_type:int",
        "from_node": "from_node:START_ID",
        "to_node": "to_node:END_ID",
    }
    edges_gdf = edges_gdf.rename(columns=edges_field_mapping)
    edge_attributes = [
        "uuid:string",
        "trail_type:int",
        "accessibility:int",
        "traffic_restriction:int",
        "surface_type:int",
        "structure_type:int",
        "from_node:START_ID",
        "to_node:END_ID",
        "length:float",
        "coords:string",
        ":TYPE",
    ]
    edges_gdf["length:float"] = edges.geometry.length
    edges_gdf[":TYPE"] = "EDGE"
    edges_gdf["coords:string"] = edges_gdf.geometry.apply(
        lambda geom: json.dumps(list(map(list, geom.coords)))
    )
    edges_gdf[edge_attributes].to_csv(output_edges_csv, index=False)

    logger.info(
        "pipeline done in %.2f s",
        time.perf_counter() - pipeline_start,
    )
