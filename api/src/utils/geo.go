package utils

func WGS84ToLV95(latitude, longitude float64) (x float64, y float64) {
	latSec := latitude * 3600
	lonSec := longitude * 3600

	phi := (latSec - 169028.66) / 10000
	lambda := (lonSec - 26782.5) / 10000

	y = 2600072.37 +
		211455.93*lambda -
		10938.51*lambda*phi -
		0.36*lambda*phi*phi -
		44.54*lambda*lambda*lambda

	x = 1200147.07 +
		308807.95*phi +
		3745.25*lambda*lambda +
		76.63*phi*phi -
		194.56*lambda*lambda*phi +
		119.79*phi*phi*phi

	return x, y
}
