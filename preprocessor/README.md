# Trail Graph Preprocessor

Preprocess 3D line data from a GeoDatabase into a graph representation.

The tool:
- reads a GeoDatabase (`.gdb`) layer
- extracts trail intersections and endpoints
- creates graph nodes and edges
- preserves trail attributes
- exports graph data for further processing

## Dataset

The input dataset is provided by SwissTopo:

https://data.geo.admin.ch/ch.swisstopo.swisstlm3d-wanderwege/swisstlm3d-wanderwege/swisstlm3d-wanderwege_2056_5728.gdb.zip

## Installation
```bash
uv sync
```

## Create a sample dataset

Extract a subset of the original GeoDatabase:
```
uv run sample ./data/SWISSTLM3D_WANDERWEGE.gdb ./data/sample_wanderwege.gdb TLM_STRASSE 2549964 1129324 2557827 1139584
```

Arguments:
```
sample <input_gdb> <output_gdb> <layer> <minx> <miny> <maxx> <maxy>
```

## Generate graph

The graph command takes one JSON configuration path:

```bash
uv run graph <config.json>
```

Relative `dataset` and `output_folder` paths are resolved from the directory containing the configuration file. The configured layer must contain only 3D `LineString` or `MultiLineString` geometry. The required `type` is used as the Neo4j node label, relationship type, and import ID group.

For SWISSTLM3D, this configuration preserves the existing exported fields:

```json
{
  "dataset": "data/SWISSTLM3D_WANDERWEGE.gdb",
  "layer": "TLM_STRASSE",
  "type": "trail",
  "output_folder": "data",
  "fields": {
    "UUID": "uuid:string",
    "WANDERWEGE": "trail_type:int",
    "BEFAHRBARKEIT": "accessibility:int",
    "VERKEHRSBESCHRAENKUNG": "traffic_restriction:int",
    "BELAGSART": "surface_type:int",
    "KUNSTBAUTE": "structure_type:int"
  }
}
```

Each run writes four files to `output_folder`, named from the dataset stem:
`<dataset>_nodes.gdb`, `<dataset>_nodes.csv`, `<dataset>_edges.gdb`, and
`<dataset>_edges.csv`.

## Export GeoJSON for tiles

Export a GDB layer as geometry-only WGS84 GeoJSON, the input for vector tile
generation (see the `Tiles` workflow):

```bash
uv run tiles <input_gdb> <layer> <output.geojson>
```

Example:

```bash
uv run tiles data/SWISSTLM3D_WANDERWEGE.gdb TLM_STRASSE output/wanderwege.geojson
```
