package trail

import (
	"errors"

	"github.com/LeonardJouve/trailr/api/src/database"
	"github.com/google/uuid"
	"github.com/neo4j/neo4j-go-driver/v6/neo4j"
)

const MAX_INITIAL_RANGE = 1000

func GetClosestNode(x float64, y float64, z float64) (string, error) {
	db, err := database.GetInstance()
	if err != nil {
		return "", err
	}

	records, err := database.Query(
		db,
		`
        WITH point({
            x: $x,
            y: $y,
            z: $z,
            crs: 'cartesian-3d'
        }) AS origin
        MATCH (n:Node)
        WHERE point.distance(n.location, origin) <= $range
        RETURN n
        ORDER BY point.distance(n.location, origin)
        LIMIT 1;
        `,
		map[string]any{
			"x":     x,
			"y":     y,
			"z":     z,
			"range": MAX_INITIAL_RANGE,
		},
		func(r *neo4j.Record) (string, error) {
			value, ok := r.Get("id")
			if !ok {
				return "", errors.New("failed to get record id")
			}

			id, ok := value.(string)
			if !ok {
				return "", errors.New("invalid id type")
			}

			return id, nil
		},
	)
	if err != nil {
		return "", err
	}

	return records[0], nil
}

func CreateGraph(origin string, radius uint) (string, error) {
	db, err := database.GetInstance()
	if err != nil {
		return "", err
	}

	name := uuid.NewString()

	_, err = database.Query(
		db,
		`
        MATCH (origin:Node {id: $origin})
        CALL gds.graph.project.cypher(
            $name,
            '
            MATCH (n:Node)
            WHERE point.distance(n.location, $origin) <= $radius
            RETURN id(n) AS id
            ',
            '
            MATCH (a:Node)-[e:EDGE]-(b:Node)
            WHERE point.distance(a.location, $origin) <= $radius
            AND point.distance(b.location, $origin) <= $radius
            RETURN
                id(a) AS source,
                id(b) AS target,
                e.length AS length
            ',
            {
                parameters: {
                    origin: origin.location,
                    radius: $radius
                }
            }
        )
        YIELD graphName
        RETURN graphName;
        `,
		map[string]any{
			"origin": origin,
			"name":   name,
			"radius": radius,
		},
		func(r *neo4j.Record) (struct{}, error) {
			return struct{}{}, nil
		},
	)

	return name, err
}

func DropGraph(name string) error {
	db, err := database.GetInstance()
	if err != nil {
		return err
	}

	_, err = database.Query(
		db,
		`
        CALL gds.graph.drop($name)
        YIELD graphName
        RETURN graphName;
        `,
		map[string]any{
			"name": name,
		},
		func(r *neo4j.Record) (struct{}, error) {
			return struct{}{}, nil
		},
	)

	return err
}

func GetReachableGraph(origin string, graph string, radius uint) error {
	db, err := database.GetInstance()
	if err != nil {
		return err
	}

	_, err = database.Query(
		db,
		`
        MATCH (origin:Node {id: $origin})
        CALL gds.allShortestPaths.dijkstra.stream($graph, {
            sourceNode: origin,
            relationshipWeightProperty: 'length'
        })
        YIELD path, totalCost
        WHERE totalCost <= $radius
        UNWIND nodes(path) AS n
        WITH collect(DISTINCT n) AS nodes
        UNWIND nodes AS a
        MATCH (a)-[e:EDGE]-(b:Node)
        WHERE b IN nodes
        RETURN DISTINCT a, e, b;
        `,
		map[string]any{
			"origin": origin,
			"graph":  graph,
			"radius": radius,
		},
		func(r *neo4j.Record) (struct{}, error) {
			return struct{}{}, nil
		},
	)

	return err

}
