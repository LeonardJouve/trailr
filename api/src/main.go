package main

import (
	"embed"
	"os"
	"strconv"

	"github.com/LeonardJouve/trailr/api/src/api"
	"github.com/LeonardJouve/trailr/api/src/database"
	"github.com/LeonardJouve/trailr/api/src/env"
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

	port, err := strconv.ParseInt(os.Getenv("API_PORT"), 10, 32)
	if err != nil {
		panic(err)
	}

	stop, err := api.Start(int(port))
	if err != nil {
		panic(err)
	}
	defer stop()
}
