# Trailr Helm chart

Requires Helm 3, Kubernetes with Traefik, and the published Trailr images.

## Install

Create the Neo4j Secret before installing. Its name is the release name followed
by `-neo4j-auth`; the username must be `neo4j`.

```powershell
$release = "trailr"
kubectl create secret generic "${release}-neo4j-auth" --from-literal=username=neo4j --from-literal=password=change-me
helm upgrade --install $release helm/trailr
```

Override settings with `--set`, for example:

```powershell
helm upgrade --install trailr helm/trailr --set host=trailr.example.com --set storage=20Gi
```

For an existing Neo4j volume, the Secret password must match the database. Changing
`storage` does not resize an existing PVC; resize that PVC directly.

## Validate

```powershell
helm lint helm/trailr
helm template trailr helm/trailr
```
