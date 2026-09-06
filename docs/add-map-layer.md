# Add a New Map Layer — Procedure

**Goal:** A repeatable, component-by-component procedure for adding a new trail-network layer (like the bike/veloland layer) end to end: dataset → graph CSVs → Neo4j → API endpoint → vector tiles → map style → Android UI.

**Architecture:** Two parallel data pipelines feed one layer. The *graph pipeline* (`preprocess.yaml` workflow, `graph.py`) turns a swisstopo GDB into node/edge CSVs imported into Neo4j; the API queries them with a `GraphType`-scoped Cypher query and exposes one `POST /<x>-tour` endpoint per layer. The *tiles pipeline* (`tiles.yaml` workflow, `tiles.py` + tippecanoe) turns the same GDB into vector tiles served by the API at `/tiles/{z}/{x}/{y}.pbf`; the API's embedded `style.json` declares one MapLibre line layer per tile layer, and the Android app toggles their visibility from the `TourType` enum. Everything between the enum entries and the Cypher queries is generic — it iterates/switches over the per-layer values, so most code needs no change, only the marked insertion points.

**Tech Stack:** Python (uv, geopandas, tippecanoe), GitHub Actions, Neo4j + GDS, Go (echo), Helm, Kotlin (Jetpack Compose, MapLibre, Retrofit).

**Spec:** The bike-layer reference implementation is commit range `4b15bb0..c07c439` (veloland/bike). Every snippet below mirrors what that range did; substitute the placeholder tokens.

## Global Constraints

Pick the layer's names **once**, before starting. They are case-sensitive contracts between components:

| Token | Meaning | Bike-layer value | Rules |
|---|---|---|---|
| `<type>` | Neo4j node label + relationship type, and the Go `GraphType` label | `bike` | lowercase, printable, no spaces, none of `();` (enforced by `graph.py` `load_config`). Must be identical in the preprocessor config `"type"` and in `trail.GraphType{...}`. |
| `<layer>` | tile layer name = GeoJSON filename stem = tippecanoe `-L` layer = `style.json` `source-layer` = `style.json` layer `id` = CSV filename prefix | `veloland` | lowercase. Use the same string for all five; `MapLayers.overlayLayerId` and `TestStyleServed` depend on it matching `style.json`. |
| `<config>` | preprocessor config filename | `veloland.json` | lives in `preprocessor/data/`. |
| `<DATASET>` | GDB stem / download name | `veloland.gdb` | must match the config's `"dataset"`. |
| `<GDB_LAYER>` | layer inside the GDB | `VeloWeg` | must exist in the GDB (`gpd.list_layers`). |
| `<ENDPOINT>` | API route suffix | `bike-tour` | `POST /<ENDPOINT>`, kebab-case. |
| `<TOURTYPE>` | Kotlin enum constant | `BIKE` | upper snake case. |
| `<Label>` | user-visible label | `Bike` | shown on segmented buttons and in trail names. |
| `<GoName>` | Go/CamelCase name | `Bike` | used in `GraphTypeBike`, `findBikeTour`. |

**Do NOT change (generic over layers):** `preprocessor/src/preprocessor/graph.py`, `preprocessor/src/preprocessor/tiles.py`, the Cypher queries in `api/src/trail/trail.go` (they are parameterized by `GraphType`), the solver and `trailr.proto` (layer-agnostic), `TrailMap.kt` and `TrailMenu.kt` segmented-button rows (they iterate `TourType.entries`), `docker-compose.yaml`, `api/.env.example`, `NetworkModule.kt`, `OfflineManager.kt` (offline regions follow `STYLE_URL`, which now contains all layers).

**Operational notes:**
- Neo4j import is one-shot: the Helm chart records completion on the Neo4j data volume, and local `docker compose` imports into the volume once. Adding a layer to an *existing* deployment requires re-importing (delete the PVC / volume) — see Task 8.
- Tiles are re-downloaded at every API pod start, so a new tiles release only needs `kubectl rollout restart`.
- Deploy order on release: chart first, then images (already documented in `helm/trailr/README.md`).

---

### Task 1: Preprocessor dataset config

**Files:**
- Create: `preprocessor/data/<config>`

**Interfaces:**
- Consumes: the swisstopo GDB download URL and its internal layer name.
- Produces: `<type>` (the Neo4j label/relationship-type string) consumed by Task 2 and Task 5; `<DATASET>`/`<GDB_LAYER>` consumed by Tasks 2 and 3.

- [ ] **Step 1: Inspect the dataset**

Download the GDB zip from [data.geo.admin.ch](https://data.geo.admin.ch), extract it, and list its layers and fields:

```bash
cd preprocessor
uv run python -c "import geopandas as gpd; print(gpd.list_layers('data/<DATASET>'))"
```

Pick the line layer (`<GDB_LAYER>`) and the fields worth keeping. Geometry must be 3D lines (`validate_input` rejects anything else).

- [ ] **Step 2: Write the config**

Mirror `preprocessor/data/veloland.json`:

```json
{
  "dataset": "<DATASET>",
  "layer": "<GDB_LAYER>",
  "type": "<type>",
  "output_folder": ".",
  "fields": {
    "<SOURCE_FIELD>": "<target_name>:string"
  }
}
```

`fields` maps GDB column names to output CSV column names; at minimum include a `uuid:string` field (edge identity). Targets must not collide with generated edge fields (`from_node:START_ID(<type>)`, `to_node:END_ID(<type>)`, `length:float`, `coords:string`, `:TYPE`).

- [ ] **Step 3: Verify locally**

With the GDB in `preprocessor/data/`:

```bash
uv run graph data/<config>
```

Expected: writes `data/<DATASET stem>_nodes.csv`, `_nodes.gdb`, `_edges.csv`, `_edges.gdb`. Check the CSV headers: nodes have `id:ID(<type>)` and `:LABEL` = `<type>`; edges have `:TYPE` = `<type>`.

- [ ] **Step 4: Commit**

```bash
git add preprocessor/data/<config>
git commit -m "preprocessor: add <layer> dataset config"
```

### Task 2: Graph CI (preprocess workflow)

**Files:**
- Modify: `.github/workflows/preprocess.yaml`

**Interfaces:**
- Consumes: `<config>`, `<DATASET>`, download URL from Task 1.
- Produces: `output/<layer>_nodes.csv` and `output/<layer>_edges.csv` inside the `trails.zip` release asset, consumed by Task 4 (imports).

- [ ] **Step 1: Add download + extract steps**

Copy the veloland block in `.github/workflows/preprocess.yaml` (job `preprocess`, after the existing dataset steps). Extraction quirks vary per dataset — veloland's zip nests the GDB in a folder, hence the `mkdir`/`mv`:

```yaml
      - name: Download <layer> dataset
        run: |
          wget \
            -O data/<layer>.gdb.zip \
            "<GDB_ZIP_URL>"

      - name: Extract <layer> GDB
        run: |
          mkdir -p data/<layer>
          bsdtar -xf data/<layer>.gdb.zip -C data/<layer>/
          mv data/<layer>/*.gdb data/<DATASET>
```

- [ ] **Step 2: Add the graph run + output rename steps**

Output files are named after the GDB stem; the release convention is `<layer>_*`:

```yaml
      - name: Run <layer> preprocessor
        run: |
          uv run graph data/<config>
          for suffix in nodes.csv nodes.gdb edges.csv edges.gdb; do
            mv "data/<DATASET stem>_${suffix}" "output/<layer>_${suffix}"
          done
```

The existing `Upload output` / release steps zip all of `output/` into `trails.zip` — no change needed there.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/preprocess.yaml
git commit -m "ci: preprocess <layer> graph into trails.zip"
```

### Task 3: Tiles CI + local tiles image

**Files:**
- Modify: `.github/workflows/tiles.yaml`
- Modify: `preprocessor/Dockerfile` (final `CMD` line)
- Modify: `preprocessor/README.md` (only if it enumerates layers — the "Generate vector tiles locally" section quotes the tippecanoe layers)

**Interfaces:**
- Consumes: `<DATASET>`, `<GDB_LAYER>` from Task 1.
- Produces: a tile layer named `<layer>` inside the single `trails` tileset (`trails-tiles.zip` release asset), consumed by Task 6 (`style.json` `source-layer`).

- [ ] **Step 1: Add download + extract steps to `tiles.yaml`**

Same pattern as Task 2 Step 1 (the tiles job re-downloads the GDB; duplicate the veloland `Download`/`Extract` steps with `<layer>` names).

- [ ] **Step 2: Add the GeoJSON export step**

```yaml
      - name: Export <layer> GeoJSON
        run: |
          uv run tiles data/<DATASET> <GDB_LAYER> output/<layer>.geojson
```

(`tiles.py` is generic: GDB + layer name in, WGS84 geometry-only GeoJSON out. Add this line to the existing `Export GeoJSON` step instead of a new step if preferred.)

- [ ] **Step 3: Add the layer to tippecanoe**

In the `Generate vector tiles` step, append one `-L` flag to the existing invocation (all layers are baked into one tileset):

```
            -L '{"file":"output/<layer>.geojson","layer":"<layer>"}'
```

- [ ] **Step 4: Mirror in `preprocessor/Dockerfile`**

The container `CMD` runs the same tippecanoe invocation for local dev; append the same `-L` pair:

```dockerfile
CMD ["tippecanoe", "-e", "data/tiles", "-Z8", "-z15", "--force", "-L", "{\"file\":\"data/wanderwege.geojson\",\"layer\":\"wanderwege\"}", "-L", "{\"file\":\"data/veloland.geojson\",\"layer\":\"veloland\"}", "-L", "{\"file\":\"data/<layer>.geojson\",\"layer\":\"<layer>\"}"]
```

- [ ] **Step 5: Verify locally**

```bash
cd preprocessor
uv run tiles data/<DATASET> <GDB_LAYER> data/<layer>.geojson
docker build -t trailr-tiles .
docker run --rm -v ".:/work" trailr-tiles
```

Expected: `data/tiles/{8..15}/{x}/{y}.pbf` regenerated. With `docker compose up`, the API serves them and `curl localhost:$API_PORT/tiles/8/134/90/1.pbf -o t.pbf` returns gzip bytes.

- [ ] **Step 6: Commit**

```bash
git add .github/workflows/tiles.yaml preprocessor/Dockerfile preprocessor/README.md
git commit -m "ci: generate <layer> vector tiles"
```

### Task 4: Neo4j import (local + Helm)

**Files:**
- Modify: `README.md` (the `neo4j-admin database import full` command)
- Modify: `helm/trailr/templates/neo4j.yaml` (three places in the `download-import` init container and the `import-database` script)

**Interfaces:**
- Consumes: `output/<layer>_nodes.csv`, `output/<layer>_edges.csv` from Task 2's `trails.zip`.
- Produces: Neo4j nodes labelled `Node:<type>` and relationships of type `<type>` (uppercase-sensitive; written by the CSV `:LABEL`/`:TYPE` headers), queried by Task 5.

- [ ] **Step 1: Update the local import command in `README.md`**

Add one `--nodes` and one `--relationships` flag:

```
docker compose run --rm neo4j neo4j-admin database import full neo4j --nodes=Node=/var/lib/neo4j/import/wanderwege_nodes.csv --nodes=Node=/var/lib/neo4j/import/veloland_nodes.csv --nodes=Node=/var/lib/neo4j/import/<layer>_nodes.csv --relationships=/var/lib/neo4j/import/wanderwege_edges.csv --relationships=/var/lib/neo4j/import/veloland_edges.csv --relationships=/var/lib/neo4j/import/<layer>_edges.csv --overwrite-destination
```

- [ ] **Step 2: Update `helm/trailr/templates/neo4j.yaml`**

Three insertion points, all following the veloland precedent:

1. `download-import` container — add `output/<layer>_nodes.csv` and `output/<layer>_edges.csv` to the `unzip -oq /import/trails.zip` file list.
2. Same container — add the two `test -s /import/output/<layer>_*.csv` guards.
3. `import-database` script — add `--nodes=Node=/import/output/<layer>_nodes.csv` and `--relationships=/import/output/<layer>_edges.csv` to the `neo4j-admin database import full` invocation.

- [ ] **Step 3: Update `helm/trailr/README.md`**

The bootstrap paragraph enumerates the imported networks ("extracts the wanderwege and veloland node and relationship CSV files") — add `<layer>`.

- [ ] **Step 4: Commit**

```bash
git add README.md helm/trailr/templates/neo4j.yaml helm/trailr/README.md
git commit -m "helm: import <layer> graph csvs"
```

### Task 5: API — graph type and tour endpoint

**Files:**
- Modify: `api/src/trail/trail.go` (the `var` block under `GraphType`)
- Modify: `api/src/api/api.go` (handler + route registration in `newServer`)
- Test: `api/src/api/api_test.go`

**Interfaces:**
- Consumes: Neo4j labels from Task 4 (`<type>`).
- Produces: `trail.GraphType<GoName>` (consumed by nothing else — it stays inside the API) and `POST /<ENDPOINT>` with the existing `TourRequest`/`TrailResponse` JSON contract (consumed by Task 7's Retrofit method).

- [ ] **Step 1: Add the GraphType**

In `api/src/trail/trail.go`:

```go
var (
	GraphTypeTrail = GraphType{"trail"}
	GraphTypeBike  = GraphType{"bike"}
	GraphType<GoName> = GraphType{"<type>"}
)
```

`<type>` must byte-match the preprocessor config's `"type"` (Task 1) — the Cypher queries interpolate it as a label/relationship type and Neo4j identifiers are case-sensitive. No query changes; `GetClosestNode`, `CreateGraph`, `GetReachableGraph` are already parameterized.

- [ ] **Step 2: Write the failing route test**

Add to `api/src/api/api_test.go` (mirrors how the existing tests hit `newServer` directly — no DB needed for a route-existence check; the handler would 500 on DB access, a 404 means the route is missing):

```go
func Test<GoName>TourRouteRegistered(t *testing.T) {
	server := newServer(t.TempDir())

	request := httptest.NewRequest(http.MethodPost, "/<ENDPOINT>", strings.NewReader("{}"))
	request.Header.Set("Content-Type", "application/json")
	recorder := httptest.NewRecorder()

	server.ServeHTTP(recorder, request)

	if recorder.Code == http.StatusNotFound {
		t.Fatalf("POST /<ENDPOINT> is not registered")
	}
}
```

(e.g. `TestSkiTourRouteRegistered` for a ski layer. An empty body fails validation with 400, which is fine — the assertion is only "not 404". This test needs no Neo4j; validation happens before any DB call.)

- [ ] **Step 3: Run test to verify it fails**

Run: `cd api; go test ./src/api/ -run Test<GoName>TourRouteRegistered -v`
Expected: FAIL with "POST /<ENDPOINT> is not registered" (404).

- [ ] **Step 4: Add the handler and route**

In `api/src/api/api.go`, next to `findBikeTour`:

```go
func find<GoName>Tour(c *echo.Context) error {
	return findTour(c, trail.GraphType<GoName>)
}
```

And in `newServer`, next to the existing tour routes:

```go
	e.POST("/<ENDPOINT>", find<GoName>Tour)
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd api; go test ./src/api/ -v`
Expected: PASS (all tests, including the existing tiles/style ones).

- [ ] **Step 6: Commit**

```bash
git add api/src/trail/trail.go api/src/api/api.go api/src/api/api_test.go
git commit -m "api: add <ENDPOINT> endpoint"
```

### Task 6: API — map style layer

**Files:**
- Modify: `api/src/api/style.json`
- Test: `api/src/api/api_test.go` (`TestStyleServed`)

**Interfaces:**
- Consumes: tile layer name `<layer>` from Task 3 (as `source-layer`; the `trails` source already exists and covers all layers).
- Produces: a style layer with `id` = `<layer>`, consumed by Task 7's `MapLayers.overlayLayerId` and toggled by `TrailMap.kt` visibility.

- [ ] **Step 1: Extend the failing style test**

In `TestStyleServed`, add `"<layer>"` to the checked source-layer list:

```go
	for _, layer := range []string{`"wanderwege"`, `"veloland"`, `"<layer>"`} {
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd api; go test ./src/api/ -run TestStyleServed -v`
Expected: FAIL with `style is missing source-layer "<layer>"`.

- [ ] **Step 3: Add the style layer**

Append to the `layers` array in `api/src/api/style.json` (hidden by default — `TrailMap.kt` shows only the selected `TourType`; HIKING is the initial selection and its layer is the only visible one):

```json
    {
      "id": "<layer>",
      "type": "line",
      "source": "trails",
      "source-layer": "<layer>",
      "layout": {
        "visibility": "none"
      },
      "paint": {
        "line-color": "#2196F3",
        "line-width": 2
      }
    }
```

Pick a distinct `line-color` per layer if they should be visually distinguishable.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd api; go test ./src/api/ -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add api/src/api/style.json api/src/api/api_test.go
git commit -m "api: serve <layer> style layer"
```

### Task 7: Android — tour type wiring

**Files:**
- Modify: `android/app/src/main/java/ch/trailer/android/api/TourType.kt`
- Modify: `android/app/src/main/java/ch/trailer/android/domain/MapLayers.kt`
- Modify: `android/app/src/main/java/ch/trailer/android/api/TrailApi.kt`
- Modify: `android/app/src/main/java/ch/trailer/android/api/TrailRepository.kt`
- Modify (only if the special case no longer reads well): `android/app/src/main/java/ch/trailer/android/components/TrailMenu.kt` (the "Find trail" button label)

**Interfaces:**
- Consumes: `POST /<ENDPOINT>` from Task 5; style layer id `<layer>` from Task 6.
- Produces: `TourType.<TOURTYPE>` — `TrailMap.kt` (segmented overlay toggle) and `TrailMenu.kt` (segmented type picker) pick it up automatically via `TourType.entries`; `TrailViewModel` uses `type.label` for saved trail names.

- [ ] **Step 1: Add the enum entry**

`TourType.kt`:

```kotlin
enum class TourType(val label: String) {
    HIKING("Hike"),
    BIKE("Bike"),
    <TOURTYPE>("<Label>")
}
```

- [ ] **Step 2: Add the overlay layer mapping**

`MapLayers.kt` — the value must equal the `style.json` layer `id` from Task 6:

```kotlin
    fun overlayLayerId(type: TourType): String = when (type) {
        TourType.HIKING -> "swisstlm3d-wanderwege"
        TourType.BIKE -> "veloland"
        TourType.<TOURTYPE> -> "<layer>"
    }
```

- [ ] **Step 3: Add the Retrofit method**

`TrailApi.kt`:

```kotlin
    @POST("<ENDPOINT>")
    suspend fun find<GoName>Tour(@Body request: TrailRequest): TrailResponse
```

- [ ] **Step 4: Add the repository branch**

`TrailRepository.kt` — the `when` must stay exhaustive (it is the compiler-enforced checklist for this task):

```kotlin
    suspend fun findTour(type: TourType, request: TrailRequest): TrailResponse {
        return when (type) {
            TourType.HIKING -> api.findHikingTour(request)
            TourType.BIKE -> api.findBikeTour(request)
            TourType.<TOURTYPE> -> api.find<GoName>Tour(request)
        }
    }
```

- [ ] **Step 5: Check the TrailMenu button label**

`TrailMenu.kt` currently hardcodes `if (selectedType == TourType.BIKE) "Find bike tour" else "Find hike"`. With a third type this special case is wrong; replace it with the generic form:

```kotlin
                Text("Find ${selectedType.label.lowercase()}")
```

- [ ] **Step 6: Verify build**

Run: `cd android; ./gradlew assembleDebug` (Windows: `.\gradlew.bat assembleDebug`)
Expected: BUILD SUCCESSFUL — a non-exhaustive `when` in Step 4 would fail compilation here.

- [ ] **Step 7: Manual smoke test**

With the API + tiles + Neo4j re-imported for the new layer (Task 8), run the app: the map overlay toggle and the find-tour sheet must show the new `<Label>` button; selecting it shows the `<layer>` overlay and `POST /<ENDPOINT>` returns a tour.

- [ ] **Step 8: Commit**

```bash
git add android/
git commit -m "android: add <Label> tour type"
```

### Task 8: Release and deployment

**Files:** none (operational procedure)

**Interfaces:**
- Consumes: everything above merged to the default branch.
- Produces: a GitHub release whose `trails.zip` and `trails-tiles.zip` assets contain the new layer, and a cluster serving it.

- [ ] **Step 1: Tag a release**

```bash
git tag v<X.Y.Z>
git push origin v<X.Y.Z>
```

Both `preprocess.yaml` and `tiles.yaml` run on `v[0-9]+.[0-9]*` tags and upload `trails.zip` / `trails-tiles.zip` (clobbering the assets on the release). Verify both workflow runs are green and the assets exist on the release.

- [ ] **Step 2: Re-import Neo4j (graph data is one-shot per volume)**

- Local: `docker compose down -v`, then re-run the Task 4 Step 1 import command, then `docker compose up -d`.
- Kubernetes: the chart skips the import when its completion marker exists on the PVC. Delete the PVC (and with it the old database) and let the statefulset re-bootstrap:

```bash
kubectl delete pod -n trailr trailr-neo4j-0
kubectl delete pvc -n trailr data-trailr-neo4j-0
```

Watch: `kubectl logs -n trailr trailr-neo4j-0 -c download-import -f` then `-c import-database -f`.

- [ ] **Step 3: Roll the API**

Tiles re-download at pod start; a restart is enough (no chart change was needed for the new layer — the `download-tiles` init container is layer-agnostic):

```bash
kubectl rollout restart deployment -n trailr trailr-api
```

- [ ] **Step 4: Verify end to end**

```bash
curl -s https://<host>/style.json | grep <layer>          # style layer present
curl -sI https://<host>/tiles/8/134/90/1.pbf | grep -i content-encoding   # gzip
curl -s -X POST https://<host>/<ENDPOINT> -H 'Content-Type: application/json' \
  -d '{"latitude":46.5,"longitude":7.4,"length":10000,"elevation":500}'   # tour JSON
```

Then install the new APK build (the `Release Android APK` workflow) and repeat the Task 7 Step 7 smoke test against production.
