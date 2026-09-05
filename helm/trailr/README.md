# Trailr Helm chart

Requires Helm 3, Kubernetes with Traefik, and the published Trailr images.

## Install

Create a namespace

```powershell
kubectl create namespace trailr
```

Create the Neo4j Secret before installing. Its name is `neo4j-auth`; the username must be `neo4j`.

```powershell
kubectl create secret generic neo4j-auth -n trailr --from-literal=username=neo4j --from-literal=password=change-me
```

Install the helm chart

```powershell
helm install trailr -n trailr -f helm/trailr/values.yaml helm/trailr
```

## Bootstrap Neo4j

The preprocessing workflow publishes `trails.zip` on each GitHub release. To initialize a fresh Neo4j volume from the latest release, enable the bootstrap init containers:

```powershell
helm install trailr -n trailr -f helm/trailr/values.yaml --set neo4jBootstrap.enabled=true helm/trailr
```

The chart downloads `https://github.com/LeonardJouve/trailr/releases/latest/download/trails.zip`, extracts the wanderwege and veloland node and relationship CSV files, and runs `neo4j-admin database import full` before Neo4j starts. It records successful completion on the Neo4j data volume, so replacement pods do not repeat the import.

For reproducible deployments, override `neo4jBootstrap.url` with a versioned release URL and set `neo4jBootstrap.sha256` to the release asset digest. Both GitHub's `sha256:<digest>` format and a bare hexadecimal digest are accepted. Digest verification is disabled by default because the contents of the latest release URL change. The importer limits Neo4j's off-heap memory to `neo4jBootstrap.maxOffHeapMemory`, which defaults to `1G`.

Bootstrap refuses to overwrite an existing database. When replacing an existing disposable database, first stop Neo4j and delete its PVC:

```powershell
kubectl scale statefulset trailr-neo4j -n trailr --replicas=0
kubectl delete pvc data-trailr-neo4j-0 -n trailr
helm upgrade trailr -n trailr -f helm/trailr/values.yaml --set neo4jBootstrap.enabled=true helm/trailr
kubectl scale statefulset trailr-neo4j -n trailr --replicas=1
```

Deleting the PVC permanently deletes the current Neo4j database. To inspect bootstrap failures, use:

```powershell
kubectl logs -n trailr trailr-neo4j-0 -c download-import
kubectl logs -n trailr trailr-neo4j-0 -c import-database
```

## Trail tiles

The API serves vector tiles at `/tiles/{z}/{x}/{y}.pbf` and needs a tiles
directory at startup. A `download-tiles` init container fetches
`trails-tiles.zip` from the GitHub release into a pod-local volume, moves the
extracted `{z}/{x}/{y}.pbf` tree to the volume root, and the API container
serves it read-only via `TILES_DIR=/tiles`, so tiles are loaded at runtime and
each pod re-downloads and unpacks the archive when it starts.

The default source is
`https://github.com/LeonardJouve/trailr/releases/latest/download/trails-tiles.zip`,
overridable with `tilesBootstrap.url`. For reproducible deployments set
`tilesBootstrap.url` to a versioned release URL and `tilesBootstrap.sha256` to
the asset digest; both GitHub's
`sha256:<digest>` format and a bare hexadecimal digest are accepted. Digest
verification is disabled by default because the contents of the latest
release URL change. Deploy this chart revision before any release whose image
requires `TILES_DIR`; pods started with an older chart crash-loop because the
API demands the variable.
