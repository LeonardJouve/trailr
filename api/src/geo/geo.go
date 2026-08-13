package geo

import "github.com/LeonardJouve/trailr/api/src/proto"

type GeoJSONFeature struct {
	Type     string                 `json:"type"`
	Geometry GeoJSONMultiLineString `json:"geometry"`
}

type GeoJSONMultiLineString struct {
	Type        string        `json:"type"`
	Coordinates [][][]float64 `json:"coordinates"`
}

func WGS84ToLV95(latitude, longitude float64) (float64, float64) {
	latSec := latitude * 3600
	lonSec := longitude * 3600

	phi := (latSec - 169028.66) / 10000
	lambda := (lonSec - 26782.5) / 10000

	x := 2600072.37 +
		211455.93*lambda -
		10938.51*lambda*phi -
		0.36*lambda*phi*phi -
		44.54*lambda*lambda*lambda

	y := 1200147.07 +
		308807.95*phi +
		3745.25*lambda*lambda +
		76.63*phi*phi -
		194.56*lambda*lambda*phi +
		119.79*phi*phi*phi

	return x, y
}

const arcSecondsPerDegree = 100.0 / 36.0

func LV95ToWGS84(easting float64, northing float64) (float64, float64) {
	// Shift to the civilian system (Bern = 0) and scale to units of 1000 km.
	y := (easting - 2600000.0) / 1000000.0
	x := (northing - 1200000.0) / 1000000.0

	lambda := 2.6779094 +
		4.728982*y +
		0.791484*y*x +
		0.1306*y*x*x -
		0.0436*y*y*y

	phi := 16.9023892 +
		3.238272*x -
		0.270978*y*y -
		0.002528*x*x -
		0.0447*y*y*x -
		0.0140*x*x*x

	return lambda * arcSecondsPerDegree, phi * arcSecondsPerDegree
}

func EdgesToGeoJSON(edges []*proto.Edge) GeoJSONFeature {
	lines := make([][][]float64, 0, len(edges))

	for _, edge := range edges {
		line := make([][]float64, 0, len(edge.Coordinates))

		for _, coordinate := range edge.Coordinates {
			longitude, latitude := LV95ToWGS84(
				coordinate.X,
				coordinate.Y,
			)

			line = append(line, []float64{
				longitude,
				latitude,
			})
		}

		lines = append(lines, line)
	}

	return GeoJSONFeature{
		Type: "Feature",
		Geometry: GeoJSONMultiLineString{
			Type:        "MultiLineString",
			Coordinates: lines,
		},
	}
}
