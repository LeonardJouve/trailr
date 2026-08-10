```
docker compose run --rm neo4j neo4j-admin database import full neo4j --nodes=Node=/var/lib/neo4j/import/wanderwege_nodes.csv --relationships=/var/lib/neo4j/import/wanderwege_edges.csv --overwrite-destination
```

// set location point property
MATCH (n:Node)
SET n.location = point({
    x: n.x,
    y: n.y,
    z: n.z,
    crs: 'cartesian-3d'
});

// create location index
CREATE POINT INDEX node_location_index
FOR (n:Node)
ON (n.location);

// query closest point to coordinate
WITH point({
    x: $x,
    y: $y,
    z: $z,
    crs: 'cartesian-3d'
}) AS origin
MATCH (n:Node)
WHERE point.distance(n.location, origin) <= 1000
RETURN n
ORDER BY point.distance(n.location, origin)
LIMIT 1;

// create graph
MATCH (source:Node {id: "1"})
CALL gds.graph.project.cypher(
    'local',
    'MATCH (n:Node)
     WHERE point.distance(n.location, $origin) <= $radius
     RETURN id(n) AS id',
    'MATCH (a:Node)-[e:EDGE]-(b:Node)
     WHERE point.distance(a.location, $origin) <= $radius
       AND point.distance(b.location, $origin) <= $radius
     RETURN id(a) AS source,
            id(b) AS target,
            e.length AS length',
    {
        parameters: {
            origin: source.location,
            radius: 1000.0
        }
    }
)
YIELD graphName, nodeCount, relationshipCount
RETURN graphName, nodeCount, relationshipCount;

// get reachable nodes
MATCH (source:Node {id: "1"})
CALL gds.allShortestPaths.dijkstra.stream('local', {
    sourceNode: source,
    relationshipWeightProperty: 'length'
})
YIELD path, totalCost
WHERE totalCost <= 1000
UNWIND nodes(path) AS n
WITH collect(DISTINCT n) AS nodes
UNWIND nodes AS a
MATCH (a)-[e:EDGE]-(b:Node)
WHERE b IN nodes
RETURN DISTINCT a, e, b;
