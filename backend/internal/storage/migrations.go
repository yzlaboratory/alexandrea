package storage

import (
	"database/sql"
	"fmt"

	"github.com/pressly/goose/v3"

	"github.com/yzlaboratory/entertainment-library/backend/db"
)

func Migrate(sqlDB *sql.DB) error {
	goose.SetBaseFS(db.Migrations)
	if err := goose.SetDialect("sqlite3"); err != nil {
		return fmt.Errorf("set dialect: %w", err)
	}
	if err := goose.Up(sqlDB, "migrations"); err != nil {
		return fmt.Errorf("goose up: %w", err)
	}
	return nil
}
