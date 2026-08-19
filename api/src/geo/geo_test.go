package geo

import (
	"reflect"
	"testing"

	"github.com/LeonardJouve/trailr/api/src/proto"
)

func TestEdgesToGeoJSONFollowsTraversalDirection(t *testing.T) {
	edge := &proto.Edge{
		FromNode: 1,
		ToNode:   2,
		Coordinates: []*proto.Coordinate{
			{X: 2600000, Y: 1200000, Z: 500},
			{X: 2600100, Y: 1200100, Z: 600},
		},
	}

	coordinates := EdgesToGeoJSON([]*proto.Edge{edge, edge}, []int32{1, 2, 1}).Geometry.Coordinates

	expectedReversed := [][]float64{coordinates[0][1], coordinates[0][0]}
	if !reflect.DeepEqual(coordinates[1], expectedReversed) {
		t.Fatalf("expected return edge to be reversed, got %v", coordinates)
	}
}

func TestEdgesToGeoJSONIncludesElevation(t *testing.T) {
	edge := &proto.Edge{
		FromNode: 1,
		ToNode:   2,
		Coordinates: []*proto.Coordinate{
			{X: 2600000, Y: 1200000, Z: 500},
			{X: 2600100, Y: 1200100, Z: 600},
		},
	}

	coordinates := EdgesToGeoJSON([]*proto.Edge{edge}, []int32{1, 2}).Geometry.Coordinates

	if len(coordinates[0][0]) != 3 {
		t.Fatalf("expected 3 coordinates, got %d", len(coordinates[0][0]))
	}

	if coordinates[0][0][2] != 500 {
		t.Fatalf("expected elevation 500, got %f", coordinates[0][0][2])
	}

	if coordinates[0][1][2] != 600 {
		t.Fatalf("expected elevation 600, got %f", coordinates[0][1][2])
	}
}
