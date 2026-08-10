package database

import (
	"embed"
	"errors"
	"fmt"
	"io/fs"
	"path/filepath"
	"sort"
	"strconv"
	"strings"

	"github.com/neo4j/neo4j-go-driver/v6/neo4j"
)

type Migration struct {
	Version uint16
	Name    string
	Query   string
}

func (d *Database) getSchemaVersion() (uint16, error) {
	records, err := Query(
		d,
		`
		MATCH (s:Schema {id: "schema"})
		RETURN s.version AS version
		`,
		map[string]any{},
		func(r *neo4j.Record) (uint16, error) {
			versionAttribute, ok := r.Get("version")
			if !ok {
				return 0, errors.New("failed to get record version")
			}

			version, ok := versionAttribute.(uint16)
			if !ok {
				return 0, errors.New("invalid version attribute type")
			}

			return version, nil
		},
	)
	if err != nil {
		return 0, err
	}

	if len(records) == 0 {
		return 0, nil
	}

	return records[0], nil
}

func (d *Database) setSchemaVersion(version uint16) error {
	_, err := neo4j.ExecuteQuery(
		d.ctx,
		d.driver,
		`
		MERGE (s:Schema {id: "schema"})
		SET s.version = $version
		`,
		map[string]any{
			"version": version,
		},
		neo4j.EagerResultTransformer,
	)

	return err
}

func getMigrations(migrationsFS embed.FS) ([]Migration, error) {
	entries, err := fs.ReadDir(migrationsFS, "migrations")
	if err != nil {
		return nil, err
	}

	migrations := make([]Migration, 0, len(entries))
	for _, entry := range entries {
		if entry.IsDir() || filepath.Ext(entry.Name()) != ".cypher" {
			continue
		}

		parts := strings.SplitN(entry.Name(), "_", 2)
		if len(parts) != 2 {
			return nil, fmt.Errorf("invalid migration filename: %q", entry.Name())
		}

		version, err := strconv.ParseUint(parts[0], 10, 16)
		if err != nil {
			return nil, fmt.Errorf("invalid migration version in %q: %w", entry.Name(), err)
		}

		query, err := fs.ReadFile(migrationsFS, "migrations/"+entry.Name())
		if err != nil {
			return nil, err
		}

		migrations = append(migrations, Migration{
			Version: uint16(version),
			Name:    strings.TrimSuffix(parts[1], ".cypher"),
			Query:   string(query),
		})
	}

	sort.Slice(migrations, func(i, j int) bool {
		return migrations[i].Version < migrations[j].Version
	})

	return migrations, nil
}

func (d *Database) Migrate(migrationFS embed.FS) error {
	version, err := d.getSchemaVersion()
	if err != nil {
		return err
	}

	migrations, err := getMigrations(migrationFS)
	if err != nil {
		return err
	}

	for _, migration := range migrations {
		if migration.Version <= version {
			continue
		}

		if _, err := Query(d, migration.Query, map[string]any{}, func(*neo4j.Record) (struct{}, error) {
			return struct{}{}, nil
		}); err != nil {
			return fmt.Errorf(
				"migration %03d_%s failed: %w",
				migration.Version,
				migration.Name,
				err,
			)
		}

		if err := d.setSchemaVersion(migration.Version); err != nil {
			return err
		}
		version = migration.Version
	}

	return nil
}
