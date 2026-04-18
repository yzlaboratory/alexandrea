// Package library is the persistence + domain layer for titles, library
// entries, and ratings. It owns SQL against the V3 content tables (see
// backend/db/migrations/00003_create_content_tables.sql) and defines the
// Go types passed up to the HTTP layer.
package library

import (
	"database/sql"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// Kind mirrors tmdb.Kind but lives in this package to avoid a downward
// dependency on the tmdb package from storage code. The values are
// identical and the string forms match the CHECK constraint on title.kind.
type Kind string

const (
	KindMovie  Kind = "movie"
	KindSeries Kind = "series"
)

// Title is one row of the title table.
type Title struct {
	ID           string    `json:"id"`
	TMDBID       int       `json:"tmdb_id"`
	Kind         Kind      `json:"kind"`
	DisplayTitle string    `json:"display_title"`
	ReleaseYear  int       `json:"release_year"`
	Synopsis     string    `json:"synopsis"`
	PosterPath   string    `json:"poster_path,omitempty"`
	AddedAt      time.Time `json:"added_at"`
}

// ErrTitleNotFound is returned when a title lookup misses.
var ErrTitleNotFound = errors.New("library: title not found")

// FindTitleByTMDBID returns the local title with the given tmdb_id, or
// ErrTitleNotFound if none exists. tmdb_id is UNIQUE in the schema, so the
// kind is informational only — callers that want to be paranoid about a
// kind mismatch should compare it after the fact.
func FindTitleByTMDBID(db *sql.DB, tmdbID int) (Title, error) {
	const q = `SELECT id, tmdb_id, kind, display_title, release_year, synopsis,
	                  COALESCE(poster_path, ''), added_at
	             FROM title WHERE tmdb_id = ?`
	var t Title
	var addedAt string
	err := db.QueryRow(q, tmdbID).Scan(
		&t.ID, &t.TMDBID, &t.Kind, &t.DisplayTitle, &t.ReleaseYear,
		&t.Synopsis, &t.PosterPath, &addedAt,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return Title{}, ErrTitleNotFound
	}
	if err != nil {
		return Title{}, fmt.Errorf("find title by tmdb_id: %w", err)
	}
	t.AddedAt, err = parseTimestamp(addedAt)
	if err != nil {
		return Title{}, err
	}
	return t, nil
}

// FindTitleByID returns the local title with the given id (the opaque
// TEXT primary key), or ErrTitleNotFound.
func FindTitleByID(db *sql.DB, id string) (Title, error) {
	const q = `SELECT id, tmdb_id, kind, display_title, release_year, synopsis,
	                  COALESCE(poster_path, ''), added_at
	             FROM title WHERE id = ?`
	var t Title
	var addedAt string
	err := db.QueryRow(q, id).Scan(
		&t.ID, &t.TMDBID, &t.Kind, &t.DisplayTitle, &t.ReleaseYear,
		&t.Synopsis, &t.PosterPath, &addedAt,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return Title{}, ErrTitleNotFound
	}
	if err != nil {
		return Title{}, fmt.Errorf("find title by id: %w", err)
	}
	t.AddedAt, err = parseTimestamp(addedAt)
	if err != nil {
		return Title{}, err
	}
	return t, nil
}

// InsertTitle creates a new title row. The caller is expected to have
// already confirmed the tmdb_id does not exist (see FindTitleByTMDBID);
// passing a duplicate surfaces the underlying UNIQUE-constraint error.
func InsertTitle(db *sql.DB, t Title) (Title, error) {
	if t.ID == "" {
		t.ID = uuid.NewString()
	}
	if t.AddedAt.IsZero() {
		t.AddedAt = time.Now().UTC()
	}
	if t.Kind != KindMovie && t.Kind != KindSeries {
		return Title{}, fmt.Errorf("library: invalid kind %q", t.Kind)
	}

	const q = `INSERT INTO title (id, tmdb_id, kind, display_title, release_year,
	                              synopsis, poster_path, added_at)
	           VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
	posterArg := sqlNullable(t.PosterPath)
	_, err := db.Exec(q, t.ID, t.TMDBID, t.Kind, t.DisplayTitle, t.ReleaseYear,
		t.Synopsis, posterArg, formatTimestamp(t.AddedAt))
	if err != nil {
		return Title{}, fmt.Errorf("insert title: %w", err)
	}
	return t, nil
}

// formatTimestamp renders a time the same way the rest of the schema does
// (RFC3339 nanoseconds, UTC).
func formatTimestamp(t time.Time) string { return t.UTC().Format(time.RFC3339Nano) }

func parseTimestamp(s string) (time.Time, error) {
	t, err := time.Parse(time.RFC3339Nano, s)
	if err != nil {
		return time.Time{}, fmt.Errorf("parse timestamp %q: %w", s, err)
	}
	return t, nil
}

// sqlNullable converts an empty string to a SQL NULL so the column actually
// stores NULL rather than the literal "" — important for poster_path which
// is nullable in the schema.
func sqlNullable(s string) any {
	if s == "" {
		return nil
	}
	return s
}
