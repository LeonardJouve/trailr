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

## Drape 2D layers with swissALTI3D elevations

Convert a 2D line layer into a 3D FileGDB by fetching the elevation of every
unique vertex from the swisstopo profile API (network required):

```bash
uv run drape <input_dataset> <layer> <output.gdb>
```

Example (the skitouren network, before running `uv run graph data/skitouren.json`):

```bash
uv run drape data/skitouren_2056.gpkg/ski_network_2056.gpkg ski_network_2056 data/skitouren.gdb
```

The input layer must be in EPSG:2056 and contain only 2D `LineString` /
`MultiLineString` geometry. Attributes, layer name and CRS are preserved;
geometries are rebuilt as XYZ.

## Export GeoJSON for tiles

Export a line layer as geometry-only WGS84 GeoJSON, the input for vector tile
generation (see the `Tiles` workflow). Any OGR-readable dataset works (FileGDB,
GeoPackage, ...); tiles carry no elevation, so a 2D source like the skitouren
GPKG can be used directly without running `drape` first:

```bash
uv run tiles <input_dataset> <layer> <output.geojson>
```

Example:

```bash
uv run tiles data/SWISSTLM3D_WANDERWEGE.gdb TLM_STRASSE data/wanderwege.geojson
uv run tiles data/skitouren_2056.gpkg/ski_network_2056.gpkg ski_network_2056 data/skitouren.geojson
```

## Generate vector tiles locally with Docker

The PBF generation step of the `Tiles` workflow can be run locally in a
container. Export the GeoJSON files into `data/` as above, then build and run
the image with the project directory mounted:

```bash
docker build -t trailr-tiles .
docker run --rm -v ".:/work" trailr-tiles
```

The container runs the same tippecanoe invocation as the workflow

## Generate elevation contour tiles locally

The contour build uses one 2 m swissALTI3D COG per 1 km² cell — the newest
campaign available for that cell (43,650 COGs, about 37 GB), so reserve at
least 100 GB free. The STAC collection lists every campaign from 2019 to 2025
(80,485 COGs), and mosaicking those editions on top of each other would blend
different survey vintages, so the build selects the newest per cell and records
that choice in `data/swissalti3d/manifest.json`.

Downloads are SHA-256 verified, cached in `data/swissalti3d/`, and reused. A
connection that closes mid-body now fails as "incomplete response" and is
retried up to three times with backoff, so a transient reset no longer looks
like a bad checksum. Build the pinned tool image, then generate the full
dataset:

```bash
docker build -f Dockerfile.contours -t trailr-contours .
docker run --rm -v ".:/work" trailr-contours build
```

Output: `data/tiles/contours/{z}/{x}/{y}.pbf`, zooms 8–15. The existing local
API mount serves it at `/tiles/contours/{z}/{x}/{y}.pbf`.

For a small pipeline check:

```bash
docker run --rm -v ".:/work" trailr-contours build \
  --dem tests/fixtures/dem.asc \
  --output-dir data/tiles/contours-smoke
```

Use `--workers N` to override four parallel downloads. There are no retries
*beyond* the three per asset: if an asset still fails, the build aborts without
replacing existing contour tiles. Rerun the command afterwards — verified
cached files and `data/swissalti3d/manifest.json` are kept, so the ~5 minute
STAC crawl is skipped unless you pass `--refresh-manifest`. Contour tiles are
copied to the production tiles PVC manually and are never uploaded to GitHub.
Because each cell carries its own newest campaign, adjacent cells can come from
different survey years; expect small elevation seams where they meet.
