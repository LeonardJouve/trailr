package api

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/labstack/echo/v5"
	"github.com/labstack/echo/v5/middleware"
)

func healthcheck(c *echo.Context) error {
	return c.JSON(http.StatusOK, map[string]string{"status": "ok"})
}

func trail(c *echo.Context) error {
	// db, err := database.GetInstance()
	// if err != nil {
	// 	return err
	// }

	// records, err := database.Query[*database.Database](
	// 	db,
	// 	`
	//     MATCH (origin:Node{id: $id})
	//     MATCH path = (origin)-[:EDGE*]-(n:Node)
	//     WITH n, path, reduce(distance = 0, r IN relationships(path) | distance + r.length) AS distance
	//     WHERE distance <= $distance
	//     UNWIND relationships(path) AS edge
	//     RETURN DISTINCT n, edge, distance
	//     ORDER BY distance
	//     `,
	// 	map[string]any{
	// 		"id":       "TODO",
	// 		"distance": "TODO",
	// 	},
	// 	func(r *neo4j.Record) (*database.Database, error) {
	// 		return &database.Database{}, nil
	// 	},
	// )

	return nil
}

func Start(port int) (func(), error) {
	e := echo.New()

	e.Use(middleware.RequestLogger())
	e.Use(middleware.Recover())

	e.GET("/healthcheck", healthcheck)
	e.GET("/trail", trail)

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
