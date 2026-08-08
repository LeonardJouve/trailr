package main

import (
	"context"
	"fmt"
	"os"

	"github.com/LeonardJouve/trailr/api/src/env"
	"github.com/neo4j/neo4j-go-driver/v6/neo4j"
)

func main() {
	if os.Getenv("ENVIRONMENT") != "PRODUCTION" {
		restore, err := env.Load(".env")
		if err != nil {
			panic(err)
		}
		defer restore()
	}

	ctx := context.Background()

	driver, err := neo4j.NewDriver(
		os.Getenv("DATABASE_URI"),
		neo4j.BasicAuth(os.Getenv("DATABASE_USER"), os.Getenv("DATABASE_PASSWORD"), ""),
	)
	if err != nil {
		panic(err)
	}
	defer driver.Close(ctx)

	err = driver.VerifyConnectivity(ctx)
	if err != nil {
		panic(err)
	}
	fmt.Println("Connection established.")
}
