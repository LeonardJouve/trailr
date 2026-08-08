```
docker compose run --rm neo4j neo4j-admin database import full neo4j --nodes=Node=/var/lib/neo4j/import/SWISSTLM3D_WANDERWEGE_nodes.csv --relationships=/var/lib/neo4j/import/SWISSTLM3D_WANDERWEGE_edges.csv --overwrite-destination
```

