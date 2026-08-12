package trail

import (
	"errors"
	"strconv"

	"github.com/LeonardJouve/trailr/api/src/database"
	"github.com/LeonardJouve/trailr/api/src/proto"
	"github.com/google/uuid"
	"github.com/neo4j/neo4j-go-driver/v6/neo4j"
)

const MAX_INITIAL_RANGE = 1000

func GetClosestNode(x float64, y float64, z float64) (int32, error) {
	db, err := database.GetInstance()
	if err != nil {
		return 0, err
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
		func(r *neo4j.Record) (int32, error) {
			value, ok := r.Get("id")
			if !ok {
				return 0, errors.New("failed to get record id")
			}

			idString, ok := value.(string)
			if !ok {
				return 0, errors.New("invalid id type")
			}

			id, err := strconv.ParseInt(idString, 10, 32)
			if err != nil {
				return 0, err
			}

			return int32(id), nil
		},
	)
	if err != nil {
		return 0, err
	}

	return records[0], nil
}

func CreateGraph(origin int32, radius uint) (string, error) {
	db, err := database.GetInstance()
	if err != nil {
		return "", err
	}

	name := uuid.NewString()

	_, err = database.Query(
		db,
		`
        MATCH (origin:Node {id: "$origin"})
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

type graphRecord struct {
	A    *proto.Node
	B    *proto.Node
	Edge *proto.Edge
}

func GetReachableGraph(origin int32, graph string, radius uint) ([]*proto.Node, []*proto.Edge, error) {
	db, err := database.GetInstance()
	if err != nil {
		return []*proto.Node{}, []*proto.Edge{}, err
	}

	records, err := database.Query(
		db,
		`
        MATCH (origin:Node {id: "$origin"})
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
        RETURN DISTINCT
            {
                id: a.id,
                x: a.x,
                y: a.y,
                z: a.z
            } AS a,
            {
                uuid: e.uuid,
                trail_type: e.trail_type,
                surface_type: e.surface_type,
                from_node: e.from_node,
                to_node: e.to_node,
                length: e.length,
                coordinates: e.coordinates
            } AS edge,
            {
                id: b.id,
                x: b.x,
                y: b.y,
                z: b.z
            } AS b;
        `,
		map[string]any{
			"origin": origin,
			"graph":  graph,
			"radius": radius,
		},
		func(r *neo4j.Record) (graphRecord, error) {
			aValue, ok := r.Get("a")
			if !ok {
				return graphRecord{}, errors.New("missing a")
			}

			edgeValue, ok := r.Get("edge")
			if !ok {
				return graphRecord{}, errors.New("missing edge")
			}

			bValue, ok := r.Get("b")
			if !ok {
				return graphRecord{}, errors.New("missing b")
			}

			a := aValue.(map[string]any)
			edge := edgeValue.(map[string]any)
			b := bValue.(map[string]any)

			return graphRecord{
				A: &proto.Node{
					Id: a["id"].(int32),
					Coordinate: &proto.Coordinate{
						X: a["x"].(float64),
						Y: a["y"].(float64),
						Z: a["z"].(float64),
					},
				},
				B: &proto.Node{
					Id: b["id"].(int32),
					Coordinate: &proto.Coordinate{
						X: b["x"].(float64),
						Y: b["y"].(float64),
						Z: b["z"].(float64),
					},
				},
				Edge: &proto.Edge{
					Uuid:        edge["uuid"].(string),
					TrailType:   proto.TrailType(edge["trail_type"].(int32)),
					SurfaceType: proto.SurfaceType(edge["surface_type"].(int32)),
					FromNode:    edge["from_node"].(int32),
					ToNode:      edge["to_node"].(int32),
					Length:      edge["length"].(float64),
				},
			}, nil
		},
	)
	if err != nil {
		return []*proto.Node{}, []*proto.Edge{}, err
	}

	nodeMap := make(map[int32]*proto.Node)
	edgeMap := make(map[string]*proto.Edge)

	for _, record := range records {
		nodeMap[record.A.Id] = record.A
		nodeMap[record.B.Id] = record.B
		edgeMap[record.Edge.Uuid] = record.Edge
	}

	nodes := make([]*proto.Node, 0, len(nodeMap))
	for _, node := range nodeMap {
		nodes = append(nodes, node)
	}

	edges := make([]*proto.Edge, 0, len(edgeMap))
	for _, edge := range edgeMap {
		edges = append(edges, edge)
	}

	return nodes, edges, nil

}
