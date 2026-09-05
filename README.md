ch.astra.wanderland
ch.astra.veloland ch.astra.mountainbikeland
ch.swisstopo-karto.skitouren ch.swisstopo.unterkuenfte-winter

satellite layer
layer picker -> trails layer
tour, straight

docker compose up

docker compose down

docker compose run --rm neo4j neo4j-admin database import full neo4j --nodes=Node=/var/lib/neo4j/import/wanderwege_nodes.csv --nodes=Node=/var/lib/neo4j/import/veloland_nodes.csv --relationships=/var/lib/neo4j/import/wanderwege_edges.csv --relationships=/var/lib/neo4j/import/veloland_edges.csv --overwrite-destination

docker compose up -d

[Helm chart documentation](helm/trailr/README.md)

## Trail map tiles

The `Tiles` workflow (`.github/workflows/tiles.yaml`) generates vector tiles of
the swisstopo trail networks from the wanderwege and veloland GDBs. It runs on
release tags and publishes `trails-tiles.zip` as a release asset.

Tiles are loaded at runtime, not shipped inside the Docker images. The API
serves them at `/tiles/{z}/{x}/{y}.pbf` from the directory set by the
`TILES_DIR` environment variable. In Kubernetes the Helm chart downloads the
release archive into the API pod at startup (see
[helm/trailr/README.md](helm/trailr/README.md)).

For local development, `docker compose` mounts `preprocessor/data/tiles` into
the API container. If that directory is empty the API still runs; the app
simply shows no trail overlay.

### Generate the tiles locally

See `preprocessor/README.md`. First export the GeoJSON for both networks into
`preprocessor/data/`, then build and run the tiles image with the project
directory mounted:

```sh
cd preprocessor
uv run tiles data/SWISSTLM3D_WANDERWEGE.gdb TLM_STRASSE data/wanderwege.geojson
uv run tiles data/veloland.gdb VeloWeg data/veloland.geojson
docker build -t trailr-tiles .
docker run --rm -v ".:/work" trailr-tiles
```

The container compiles tippecanoe and writes the `z/x/y.pbf` tile tree to
`preprocessor/data/tiles/`, which `docker compose` picks up on the next
`docker compose up`.

## Android APK releases

The `Release Android APK` GitHub Actions workflow builds a signed APK and
publishes it to a GitHub Release. It runs manually from **Actions** and asks
for a release tag such as `v1.0.0`.

Generate a signing keystore locally:

```sh
keytool -genkeypair -v -keystore trailr-release.jks -alias trailr -keyalg RSA -keysize 2048 -validity 10000
```

Keep this file and its passwords backed up securely. Losing the keystore
prevents future APKs from being published as updates to the same app. Do not
commit it to the repository.

In the GitHub repository, open **Settings > Secrets and variables > Actions**
and add these repository secrets:

| Secret | Value |
| --- | --- |
| `API_URL` | Production API URL used by the Android app |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded contents of `trailr-release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password entered in `keytool` |
| `ANDROID_KEY_ALIAS` | `trailr` |
| `ANDROID_KEY_PASSWORD` | Key password entered in `keytool` |

On PowerShell, copy the base64-encoded keystore to the clipboard with:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("trailr-release.jks")) | Set-Clipboard
```

Then open **Actions > Release Android APK > Run workflow**, enter a new tag,
and run it.
