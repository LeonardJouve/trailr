package trail

import (
	"encoding/json"
	"errors"
	"fmt"
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
        RETURN n.id AS id
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
			"origin": strconv.FormatInt(int64(origin), 10),
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

func parseCoordinates(rawCoordinates string) ([]*proto.Coordinate, error) {
	var jsonCoordinates [][]float64
	if err := json.Unmarshal([]byte(rawCoordinates), &jsonCoordinates); err != nil {
		return []*proto.Coordinate{}, fmt.Errorf("failed to parse coordinates: %w", err)
	}

	coordinates := make([]*proto.Coordinate, 0, len(jsonCoordinates))
	for _, coordinate := range jsonCoordinates {
		if len(coordinate) != 3 {
			return []*proto.Coordinate{}, fmt.Errorf("invalid coordinate length: %d", len(coordinate))
		}

		coordinates = append(coordinates, &proto.Coordinate{
			X: coordinate[0],
			Y: coordinate[1],
			Z: coordinate[2],
		})
	}

	return coordinates, nil
}

func GetReachableGraph(origin int32, graph string, radius uint) ([]*proto.Node, []*proto.Edge, error) {
	db, err := database.GetInstance()
	if err != nil {
		return []*proto.Node{}, []*proto.Edge{}, err
	}

	records, err := database.Query(
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
                coordinates: e.coords
            } AS edge,
            {
                id: b.id,
                x: b.x,
                y: b.y,
                z: b.z
            } AS b;
        `,
		map[string]any{
			"origin": strconv.FormatInt(int64(origin), 10),
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

			aId, err := strconv.ParseInt(a["id"].(string), 10, 32)
			if err != nil {
				return graphRecord{}, fmt.Errorf("invalid a.id type: %T", a["id"])
			}

			bId, err := strconv.ParseInt(b["id"].(string), 10, 32)
			if err != nil {
				return graphRecord{}, fmt.Errorf("invalid b.id type: %T", b["id"])
			}

			trailType, ok := edge["trail_type"].(int64)
			if !ok {
				return graphRecord{}, fmt.Errorf("invalid trail_type type: %T", edge["trail_type"])
			}

			surfaceType, ok := edge["surface_type"].(int64)
			if !ok {
				return graphRecord{}, fmt.Errorf("invalid surface_type type: %T", edge["surface_type"])
			}

			rawCoordinates, ok := edge["coordinates"].(string)
			if !ok {
				return graphRecord{}, fmt.Errorf("invalid coordinates type: %T", edge["coordinates"])
			}

			coordinates, err := parseCoordinates(rawCoordinates)
			if err != nil {
				return graphRecord{}, err
			}

			return graphRecord{
				A: &proto.Node{
					Id: int32(aId),
					Coordinate: &proto.Coordinate{
						X: a["x"].(float64),
						Y: a["y"].(float64),
						Z: a["z"].(float64),
					},
				},
				B: &proto.Node{
					Id: int32(bId),
					Coordinate: &proto.Coordinate{
						X: b["x"].(float64),
						Y: b["y"].(float64),
						Z: b["z"].(float64),
					},
				},
				Edge: &proto.Edge{
					Uuid:        edge["uuid"].(string),
					TrailType:   proto.TrailType(trailType),
					SurfaceType: proto.SurfaceType(surfaceType),
					FromNode:    int32(aId),
					ToNode:      int32(bId),
					Length:      edge["length"].(float64),
					Coordinates: coordinates,
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
