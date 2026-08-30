docker compose up

docker compose run --rm neo4j neo4j-admin database import full neo4j --nodes=Node=/var/lib/neo4j/import/wanderwege_nodes.csv --relationships=/var/lib/neo4j/import/wanderwege_edges.csv --overwrite-destination

[Helm chart documentation](helm/trailr/README.md)
