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
			{X: 2600000, Y: 1200000},
			{X: 2600100, Y: 1200100},
		},
	}

	coordinates := EdgesToGeoJSON([]*proto.Edge{edge, edge}, []int32{1, 2, 1}).Geometry.Coordinates

	if !reflect.DeepEqual(coordinates[1], [][]float64{coordinates[0][1], coordinates[0][0]}) {
		t.Fatalf("expected return edge to be reversed, got %v", coordinates)
	}
}
