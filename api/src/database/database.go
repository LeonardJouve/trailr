package database

import (
	"context"
	"errors"
	"fmt"

	"github.com/neo4j/neo4j-go-driver/v6/neo4j"
)

type Database struct {
	driver neo4j.Driver
	ctx    context.Context
}

var db *Database

func New(uri string, user string, password string) (*Database, error) {
	db = &Database{
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

func GetInstance() (*Database, error) {
	if db == nil {
		return nil, errors.New("no database")
	}

	return db, nil
}

func (d *Database) Close() {
	d.driver.Close(d.ctx)
}

func Query[T any](db *Database, query string, parameters map[string]any, mapper func(*neo4j.Record) (T, error)) ([]T, error) {
	result, err := neo4j.ExecuteQuery(
		db.ctx,
		db.driver,
		query,
		parameters,
		neo4j.EagerResultTransformer,
		neo4j.ExecuteQueryWithDatabase(""),
	)
	if err != nil {
		return nil, err
	}

	records := make([]T, len(result.Records))
	for i, record := range result.Records {
		records[i], err = mapper(record)
		if err != nil {
			return nil, err
		}
	}

	fmt.Printf(
		"`%v` returned %v records in %+v.\n",
		result.Summary.Query().Text(),
		len(result.Records),
		result.Summary.ResultAvailableAfter(),
	)

	return records, nil
}
