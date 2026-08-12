package api

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

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

type TrailRequest struct {
	X         float64 `json:"x"`
	Y         float64 `json:"y"`
	Z         float64 `json:"z"`
	Length    uint    `json:"length" validate:"gt=0,lte=30000"`
	Elevation uint    `json:"elevation" validate:"gt=0,lte=10000"`
}

func findTrail(c *echo.Context) error {
	var request TrailRequest

	if err := c.Bind(&request); err != nil {
		return echo.NewHTTPError(http.StatusBadRequest, "invalid request body")
	}

	if err := validate.Struct(request); err != nil {
		return echo.NewHTTPError(http.StatusBadRequest, err.Error())
	}

	origin, err := trail.GetClosestNode(request.X, request.Y, request.Z)
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "failed to find closest node")
	}

	graph, err := trail.CreateGraph(origin, request.Length)
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "failed to create graph")
	}

	defer trail.DropGraph(graph)

	nodes, edges, err := trail.GetReachableGraph(origin, graph, request.Length)
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "failed to find reachable graph")
	}

	client, err := proto.GetInstance()
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "failed to get grpc client instance")
	}

	response, err := client.SolveTour(c.Request().Context(), &proto.SolveTourRequest{
		OriginId:        origin,
		TargetLength:    float64(request.Length),
		TargetElevation: float64(request.Elevation),
		Nodes:           nodes,
		Edges:           edges,
	})
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "failed to call solver")
	}

	if !response.Found {
		return echo.NewHTTPError(http.StatusInternalServerError, "failed solve graph")
	}

	return c.JSON(http.StatusOK, map[string]any{
		"found":     response.Found,
		"length":    response.Length,
		"elevation": response.Elevation,
		"nodeIds":   response.NodeIds,
		"edgeIds":   response.EdgeIds,
	})
}

func Start(port int) (func(), error) {
	e := echo.New()

	e.Use(middleware.RequestLogger())
	e.Use(middleware.Recover())

	e.GET("/healthcheck", healthcheck)
	e.GET("/trail", findTrail)

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
