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
