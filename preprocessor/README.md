# Trail Graph Preprocessor

Preprocess SwissTopo hiking trail data into a graph representation.

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

Run the graph preprocessing:
```
uv run graph ./data/SWISSTLM3D_WANDERWEGE.gdb ./data TLM_STRASSE
````

Arguments:
```
graph <input_gdb> <output_folder> <layer>
```
