package main

import (
	"embed"
	"errors"
	"os"
	"strconv"

	"github.com/LeonardJouve/trailr/api/src/api"
	"github.com/LeonardJouve/trailr/api/src/database"
	"github.com/LeonardJouve/trailr/api/src/env"
	"github.com/LeonardJouve/trailr/api/src/proto"
)

//go:embed database/migrations/*.cypher
var migrationFS embed.FS

func main() {
	if os.Getenv("ENVIRONMENT") != "PRODUCTION" {
		restore, err := env.Load(".env")
		if err != nil {
			panic(err)
		}
		defer restore()
	}

	db, err := database.New(os.Getenv("DATABASE_URI"), os.Getenv("DATABASE_USER"), os.Getenv("DATABASE_PASSWORD"))
	if err != nil {
		panic(err)
	}
	defer db.Close()

	if err := db.Migrate(migrationFS); err != nil {
		panic(err)
	}

	client, err := proto.New(os.Getenv("SOLVER_URI"))
	if err != nil {
		panic(err)
	}
	defer client.Close()

	port, err := strconv.ParseInt(os.Getenv("API_PORT"), 10, 32)
	if err != nil {
		panic(err)
	}

	tilesDir := os.Getenv("TILES_DIR")
	if tilesDir == "" {
		panic(errors.New("TILES_DIR not set"))
	}

	stop, err := api.Start(int(port), tilesDir)
	if err != nil {
		panic(err)
	}
	defer stop()
}
