package main

import (
	"database/sql"
	"errors"
	"flag"
	"fmt"
	"io"
	"time"

	"github.com/yzlaboratory/entertainment-library/backend/internal/auth"
	"github.com/yzlaboratory/entertainment-library/backend/internal/storage"
)

const minPasswordLen = 14

func runSeed(args []string, stdout io.Writer) error {
	fs := flag.NewFlagSet("seed", flag.ContinueOnError)
	fs.SetOutput(stdout)
	displayName := fs.String("display-name", "", "display name (required)")
	username := fs.String("username", "", "username / user id (required)")
	password := fs.String("password", "", "password (required; will be argon2id-hashed)")
	if err := fs.Parse(args); err != nil {
		return err
	}

	if *displayName == "" || *username == "" || *password == "" {
		return errors.New("--display-name, --username, and --password are all required")
	}
	if len(*password) < minPasswordLen {
		return fmt.Errorf("password must be at least %d characters", minPasswordLen)
	}

	dsn := envOr("ENTLIB_DB_DSN", "./entlib.sqlite")
	db, err := storage.Open(dsn)
	if err != nil {
		return fmt.Errorf("open db: %w", err)
	}
	defer db.Close()
	if err := storage.Migrate(db); err != nil {
		return fmt.Errorf("migrate: %w", err)
	}

	return upsertUserAndCredential(db, *username, *displayName, *password)
}

func upsertUserAndCredential(db *sql.DB, username, displayName, password string) error {
	hash, err := auth.HashPassword(password)
	if err != nil {
		return fmt.Errorf("hash password: %w", err)
	}

	tx, err := db.Begin()
	if err != nil {
		return fmt.Errorf("begin: %w", err)
	}
	defer tx.Rollback()

	if _, err := tx.Exec(
		`INSERT INTO "user" (id, display_name) VALUES (?, ?)
		 ON CONFLICT(id) DO UPDATE SET display_name = excluded.display_name`,
		username, displayName,
	); err != nil {
		return fmt.Errorf("upsert user: %w", err)
	}

	now := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err := tx.Exec(
		`INSERT INTO user_credential (user_id, password_hash, updated_at) VALUES (?, ?, ?)
		 ON CONFLICT(user_id) DO UPDATE SET password_hash = excluded.password_hash, updated_at = excluded.updated_at`,
		username, hash, now,
	); err != nil {
		return fmt.Errorf("upsert credential: %w", err)
	}

	return tx.Commit()
}
