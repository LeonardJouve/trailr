package database

import (
	"context"

	"github.com/neo4j/neo4j-go-driver/v6/neo4j"
)

type Database struct {
	driver neo4j.Driver
	ctx    context.Context
}

func New(uri string, user string, password string) (*Database, error) {
	db := &Database{
		ctx: context.Background(),
	}

	authToken := neo4j.BasicAuth(user, password, "")

	var err error
	db.driver, err = neo4j.NewDriver(uri, authToken)
	if err != nil {
		return nil, err
	}

	if err := db.driver.VerifyConnectivity(db.ctx); err != nil {
		return nil, err
	}

	return db, nil
}

func (d *Database) Close() {
	d.driver.Close(d.ctx)
}
