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

func Start(port int) (func(), error) {
	e := echo.New()

	e.Use(middleware.RequestLogger())
	e.Use(middleware.Recover())

	e.GET("/healthcheck", healthcheck)

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
