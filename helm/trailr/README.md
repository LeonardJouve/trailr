# Trailr Helm chart

## Prerequisites

- Helm 3
- K3s with Traefik
- Published Trailr images in GHCR
- An existing Kubernetes Secret containing `username` and `password` keys

## Neo4j credentials and data

The chart reads Neo4j credentials from `database.existingSecret`; it does not create
the Secret and cannot validate its contents at render time. The value stored under
`database.usernameKey` must be exactly `neo4j`, because the official Neo4j image
expects `NEO4J_AUTH=neo4j/<password>`.

For a volume containing an existing database, the Secret password must match the
credentials already stored in that database. `NEO4J_AUTH` initializes credentials
for a new database but does not reset credentials in an existing database.

A new empty PVC initializes an empty Neo4j database. Pre-existing or separately
imported data is optional, and this chart performs no CSV import.

## Neo4j storage upgrades

`neo4j.storage.size` and `neo4j.storage.storageClass` are creation-time PVC settings
rendered in the StatefulSet's `volumeClaimTemplates`. Changing either value during a
Helm upgrade does not resize or migrate an existing PVC and may fail because the
StatefulSet template is not updatable. Resize an existing PVC directly, following
the capabilities and procedure of its storage class.

## Render validation

```powershell
helm lint helm/trailr
helm template trailr helm/trailr
```
