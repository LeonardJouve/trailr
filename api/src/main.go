package main

import (
	"os"

	"github.com/LeonardJouve/trailr/api/src/database"
	"github.com/LeonardJouve/trailr/api/src/env"
)

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
}
