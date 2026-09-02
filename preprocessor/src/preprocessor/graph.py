import argparse
import json
import logging
import time
from collections import Counter
from pathlib import Path

import geopandas as gpd
import pandas as pd
from shapely.geometry import LineString, MultiPoint, Point
from shapely.ops import snap, split
from shapely.strtree import STRtree

PointKey = tuple[float, float, float]

RESERVED_SOURCE_FIELDS = {"feature_id", "from_node", "to_node"}

logger = logging.getLogger("graph")


def load_config(config_path: Path) -> tuple[Path, Path, str, dict[str, str], str]:
    config_path = config_path.resolve()
    config = json.loads(config_path.read_text(encoding="utf-8"))

    if not isinstance(config, dict):
        raise ValueError("config must be a JSON object")

    for name in ("dataset", "output_folder", "layer", "fields", "type"):
        if name not in config:
            raise ValueError(f"missing config value: {name}")

    for name in ("dataset", "output_folder", "layer", "type"):
        if not isinstance(config[name], str) or not config[name]:
            raise ValueError(f"config value '{name}' must be a non-empty string")

    if (
        config["type"] != config["type"].strip()
        or not config["type"].isprintable()
        or any(character in config["type"] for character in "();")
    ):
        raise ValueError("config value 'type' contains invalid Neo4j characters")

    if not isinstance(config["fields"], dict) or not all(
        isinstance(source, str)
        and bool(source)
        and isinstance(target, str)
        and bool(target)
        for source, target in config["fields"].items()
    ):
        raise ValueError("config value 'fields' must map non-empty strings")

    def resolve(value: str) -> Path:
        path = Path(value)
        return path if path.is_absolute() else config_path.parent / path

    dataset = resolve(config["dataset"])
    if not dataset.exists():
        raise ValueError(f"dataset does not exist: {dataset}")

    return (
        dataset,
        resolve(config["output_folder"]),
        config["layer"],
        config["fields"],
        config["type"],
    )


def validate_input(trails: gpd.GeoDataFrame, fields: dict[str, str], graph_type: str) -> None:
    missing = fields.keys() - trails.columns
    if missing:
        raise ValueError(f"layer is missing configured fields: {sorted(missing)}")

    generated_edge_fields = {
        f"from_node:START_ID({graph_type})",
        f"to_node:END_ID({graph_type})",
        "length:float",
        "coords:string",
        ":TYPE",
    }
    collisions = set(fields.values()) & generated_edge_fields
    if collisions:
        raise ValueError(
            "configured output fields collide with generated fields: "
            f"{sorted(collisions)}"
        )

    duplicates = {
        field for field, count in Counter(fields.values()).items() if count > 1
    }
    if duplicates:
        raise ValueError(f"duplicate configured output fields: {sorted(duplicates)}")

    reserved = RESERVED_SOURCE_FIELDS & set(trails.columns)
    if reserved:
        raise ValueError(f"layer contains reserved source fields: {sorted(reserved)}")

    if any(
        geometry is None
        or geometry.geom_type not in {"LineString", "MultiLineString"}
        or not geometry.has_z
        for geometry in trails.geometry
    ):
        raise ValueError("layer must contain only 3D line geometry")

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

def prepare_edge_csv(edges: gpd.GeoDataFrame, fields: dict[str, str], graph_type: str) -> pd.DataFrame:
    result = edges[list(fields)].rename(columns=fields)
    from_node_field = f"from_node:START_ID({graph_type})"
    to_node_field = f"to_node:END_ID({graph_type})"
    result[from_node_field] = edges["from_node"]
    result[to_node_field] = edges["to_node"]
    result["length:float"] = edges.geometry.length
    result[":TYPE"] = graph_type
    result["coords:string"] = edges.geometry.apply(
        lambda geometry: json.dumps(list(map(list, geometry.coords)))
    )
    return result[
        [
            *fields.values(),
            from_node_field,
            to_node_field,
            "length:float",
            "coords:string",
            ":TYPE",
        ]
    ]


def main():
    parser = argparse.ArgumentParser(description="GDB trail preprocess")
    parser.add_argument("config", type=Path, help="JSON configuration path")
    args = parser.parse_args()

    input_gdb, output_folder, layer, fields, graph_type = load_config(args.config)
    output_folder.mkdir(parents=True, exist_ok=True)

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

    available_layers = set(gpd.list_layers(input_gdb)["name"])
    if layer not in available_layers:
        raise ValueError(f"layer does not exist in dataset: {layer}")

    trails: gpd.GeoDataFrame = gpd.read_file(
        input_gdb,
        layer=layer,
        engine="pyogrio",
    )
    validate_input(trails, fields, graph_type)
    trails: gpd.GeoDataFrame = trails.reset_index().rename(
        columns={"index": "feature_id"}
    )  # ty:ignore[invalid-assignment]

    trails = (  # ty:ignore[invalid-assignment]
        trails.explode(index_parts=False).reset_index(drop=True)
    )
    logger.info("loaded %d features", len(trails))

    nodes = extract_intersections(trails) + extract_endpoints(trails)

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
        "id": f"id:ID({graph_type})",
        "x": "x:double",
        "y": "y:double",
        "z": "z:double",
    }
    nodes_gdf = nodes_gdf.rename(columns=nodes_field_mapping)
    node_attributes = [
        f"id:ID({graph_type})",
        "x:double",
        "y:double",
        "z:double",
        ":LABEL",
    ]
    nodes_gdf["x:double"] = nodes_gdf.geometry.x
    nodes_gdf["y:double"] = nodes_gdf.geometry.y
    nodes_gdf["z:double"] = nodes_gdf.geometry.z
    nodes_gdf[":LABEL"] = graph_type
    nodes_gdf[node_attributes].to_csv(output_nodes_csv, index=False)

    edges_gdf = attach_node_ids(edges, node_index)
    edges_gdf.to_file(
        output_edges_gdb,
        layer=layer,
        driver="OpenFileGDB",
        engine="pyogrio",
    )
    prepare_edge_csv(edges_gdf, fields, graph_type).to_csv(output_edges_csv, index=False)

    logger.info(
        "pipeline done in %.2f s",
        time.perf_counter() - pipeline_start,
    )
