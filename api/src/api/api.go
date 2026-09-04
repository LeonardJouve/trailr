package api

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/LeonardJouve/trailr/api/src/geo"
	"github.com/LeonardJouve/trailr/api/src/proto"
	"github.com/LeonardJouve/trailr/api/src/trail"
	"github.com/go-playground/validator/v10"
	"github.com/labstack/echo/v5"
	"github.com/labstack/echo/v5/middleware"
)

var validate = validator.New(validator.WithRequiredStructEnabled())

func healthcheck(c *echo.Context) error {
	return c.JSON(http.StatusOK, map[string]string{"status": "ok"})
}

type TourRequest struct {
	Latitude  float64 `json:"latitude"`
	Longitude float64 `json:"longitude"`
	Length    uint    `json:"length" validate:"gt=0,lte=25000"`
	Elevation uint    `json:"elevation" validate:"gte=0,lte=2000"`
}

func findTour(c *echo.Context, graphType trail.GraphType) error {
	var request TourRequest

	if err := c.Bind(&request); err != nil {
		return echo.NewHTTPError(http.StatusBadRequest, "invalid request body")
	}

	if err := validate.Struct(request); err != nil {
		return echo.NewHTTPError(http.StatusBadRequest, err.Error())
	}

	x, y := geo.WGS84ToLV95(request.Latitude, request.Longitude)

	origin, err := trail.GetClosestNode(x, y, graphType)
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "failed to find closest node")
	}

	graph, err := trail.CreateGraph(origin, request.Length, graphType)
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "failed to create graph")
	}

	defer trail.DropGraph(graph)

	nodes, edges, err := trail.GetReachableGraph(origin, graph, uint(float32(request.Length)*0.65), graphType)
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "failed to find reachable graph")
	}

	client, err := proto.GetInstance()
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "failed to get grpc client instance")
	}

	response, err := client.SolveTour(c.Request().Context(), &proto.SolveTourRequest{
		OriginId:                       origin,
		TargetLength:                   float64(request.Length),
		TargetElevation:                float64(request.Elevation),
		Nodes:                          nodes,
		Edges:                          edges,
		LengthPenaltyWeight:            1.0,
		ExponentialLengthPenaltyWeight: 20.0,
		ElevationPenaltyWeight:         4.0,
		RepeatPenaltyWeight:            2.0,
		TimeLimitSeconds:               20.0,
	})
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "failed to call solver")
	}

	if !response.Found {
		return echo.NewHTTPError(http.StatusInternalServerError, "failed solve graph")
	}

	geoJSON := geo.EdgesToGeoJSON(filterEdges(edges, response.EdgeIds), response.NodeIds)

	return c.JSON(http.StatusOK, map[string]any{
		"found":     response.Found,
		"length":    response.Length,
		"elevation": response.Elevation,
		"geoJSON":   geoJSON,
	})
}

func findHikingTour(c *echo.Context) error {
	return findTour(c, trail.GraphTypeTrail)
}

func findBikeTour(c *echo.Context) error {
	return findTour(c, trail.GraphTypeBike)
}

func filterEdges(edges []*proto.Edge, edgeUUIDs []string) []*proto.Edge {
	edgeSet := make(map[string]*proto.Edge, len(edgeUUIDs))

	for _, edge := range edges {
		edgeSet[edge.Uuid] = edge
	}

	filtered := []*proto.Edge{}

	for _, uuid := range edgeUUIDs {
		if edge, ok := edgeSet[uuid]; ok {
			filtered = append(filtered, edge)
		}
	}

	return filtered
}

func newServer(tilesDir string) *echo.Echo {
	e := echo.New()

	e.Use(middleware.RequestLogger())
	e.Use(middleware.Recover())

	e.GET("/healthcheck", healthcheck)
	e.POST("/hiking-tour", findHikingTour)
	e.POST("/bike-tour", findBikeTour)
	e.Static("/tiles", tilesDir)

	return e
}

func Start(port int) (func(), error) {
	e := newServer("tiles")

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)

	sc := echo.StartConfig{
		Address:         fmt.Sprintf(":%d", port),
		GracefulTimeout: 5 * time.Second,
	}
	if err := sc.Start(ctx, e); err != nil {
		return func() {}, err
	}

	return stop, nil
}
