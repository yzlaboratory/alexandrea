package library

import (
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
)

// Status is the four-valued state of a library entry per specs/02-library.md.
type Status string

const (
	StatusWant      Status = "want"
	StatusWatching  Status = "watching"
	StatusWatched   Status = "watched"
	StatusAbandoned Status = "abandoned"
)

// IsValid reports whether s is one of the four allowed statuses.
func (s Status) IsValid() bool {
	switch s {
	case StatusWant, StatusWatching, StatusWatched, StatusAbandoned:
		return true
	}
	return false
}

// Entry is one row of the library_entry table joined with its title row.
// JSON is shaped for the frontend; the embedded Title carries all metadata
// the UI needs to render an entry without an extra round trip.
type Entry struct {
	ID              string    `json:"id"`
	Status          Status    `json:"status"`
	AddedAt         time.Time `json:"added_at"`
	StatusUpdatedAt time.Time `json:"status_updated_at"`
	Title           Title     `json:"title"`
}

// ErrEntryNotFound is returned when a library entry lookup misses.
var ErrEntryNotFound = errors.New("library: entry not found")

// ErrEntryAlreadyExists is returned by InsertEntry when a row with the
// given title_id already exists. (library_entry.title_id is UNIQUE.)
var ErrEntryAlreadyExists = errors.New("library: entry already exists for title")

// ListEntries returns library entries filtered by status. An empty filter
// returns everything. Result order is most-recently-added first so the
// "what's on our plate right now" view feels current.
func ListEntries(db *sql.DB, statuses []Status) ([]Entry, error) {
	for _, s := range statuses {
		if !s.IsValid() {
			return nil, fmt.Errorf("library: invalid status filter %q", s)
		}
	}

	var (
		whereClause string
		args        []any
	)
	if len(statuses) > 0 {
		placeholders := make([]string, len(statuses))
		for i, s := range statuses {
			placeholders[i] = "?"
			args = append(args, string(s))
		}
		whereClause = " WHERE le.status IN (" + strings.Join(placeholders, ",") + ")"
	}

	q := `SELECT le.id, le.status, le.added_at, le.status_updated_at,
	             t.id, t.tmdb_id, t.kind, t.display_title, t.release_year,
	             t.synopsis, COALESCE(t.poster_path, ''), t.added_at
	        FROM library_entry le
	        JOIN title t ON t.id = le.title_id` + whereClause + `
	    ORDER BY le.added_at DESC`

	rows, err := db.Query(q, args...)
	if err != nil {
		return nil, fmt.Errorf("list entries: %w", err)
	}
	defer rows.Close()

	var out []Entry
	for rows.Next() {
		var (
			e               Entry
			addedAt, statAt string
			titleAddedAt    string
		)
		if err := rows.Scan(
			&e.ID, &e.Status, &addedAt, &statAt,
			&e.Title.ID, &e.Title.TMDBID, &e.Title.Kind, &e.Title.DisplayTitle,
			&e.Title.ReleaseYear, &e.Title.Synopsis, &e.Title.PosterPath, &titleAddedAt,
		); err != nil {
			return nil, fmt.Errorf("scan entry: %w", err)
		}
		if e.AddedAt, err = parseTimestamp(addedAt); err != nil {
			return nil, err
		}
		if e.StatusUpdatedAt, err = parseTimestamp(statAt); err != nil {
			return nil, err
		}
		if e.Title.AddedAt, err = parseTimestamp(titleAddedAt); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

// FindEntryByID returns one entry (joined to its title), or ErrEntryNotFound.
func FindEntryByID(db *sql.DB, id string) (Entry, error) {
	const q = `SELECT le.id, le.status, le.added_at, le.status_updated_at,
	                  t.id, t.tmdb_id, t.kind, t.display_title, t.release_year,
	                  t.synopsis, COALESCE(t.poster_path, ''), t.added_at
	             FROM library_entry le
	             JOIN title t ON t.id = le.title_id
	            WHERE le.id = ?`
	var (
		e               Entry
		addedAt, statAt string
		titleAddedAt    string
	)
	err := db.QueryRow(q, id).Scan(
		&e.ID, &e.Status, &addedAt, &statAt,
		&e.Title.ID, &e.Title.TMDBID, &e.Title.Kind, &e.Title.DisplayTitle,
		&e.Title.ReleaseYear, &e.Title.Synopsis, &e.Title.PosterPath, &titleAddedAt,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return Entry{}, ErrEntryNotFound
	}
	if err != nil {
		return Entry{}, fmt.Errorf("find entry: %w", err)
	}
	if e.AddedAt, err = parseTimestamp(addedAt); err != nil {
		return Entry{}, err
	}
	if e.StatusUpdatedAt, err = parseTimestamp(statAt); err != nil {
		return Entry{}, err
	}
	if e.Title.AddedAt, err = parseTimestamp(titleAddedAt); err != nil {
		return Entry{}, err
	}
	return e, nil
}

// InsertEntry creates a new library_entry. The caller supplies titleID and
// status; ID, AddedAt, and StatusUpdatedAt are auto-assigned. Status
// defaults to StatusWant when blank. Returns ErrEntryAlreadyExists if a
// row with the given titleID is already present.
func InsertEntry(db *sql.DB, titleID string, status Status) (Entry, error) {
	if status == "" {
		status = StatusWant
	}
	if !status.IsValid() {
		return Entry{}, fmt.Errorf("library: invalid status %q", status)
	}
	if titleID == "" {
		return Entry{}, errors.New("library: title_id is required")
	}

	now := time.Now().UTC()
	id := uuid.NewString()
	_, err := db.Exec(
		`INSERT INTO library_entry (id, title_id, status, added_at, status_updated_at)
		 VALUES (?, ?, ?, ?, ?)`,
		id, titleID, status, formatTimestamp(now), formatTimestamp(now),
	)
	if err != nil {
		// Sniff the unique-constraint failure path. modernc.org/sqlite
		// bakes "UNIQUE constraint failed" into the error string; matching
		// on substring is uglier than parsing a code, but the alternative
		// requires a driver-specific type assertion.
		if isUniqueConstraintErr(err) {
			return Entry{}, ErrEntryAlreadyExists
		}
		return Entry{}, fmt.Errorf("insert entry: %w", err)
	}
	return FindEntryByID(db, id)
}

// UpdateEntryStatus changes the status of an existing entry and bumps its
// status_updated_at. Returns ErrEntryNotFound if no row matches.
func UpdateEntryStatus(db *sql.DB, id string, status Status) (Entry, error) {
	if !status.IsValid() {
		return Entry{}, fmt.Errorf("library: invalid status %q", status)
	}
	res, err := db.Exec(
		`UPDATE library_entry SET status = ?, status_updated_at = ? WHERE id = ?`,
		status, formatTimestamp(time.Now().UTC()), id,
	)
	if err != nil {
		return Entry{}, fmt.Errorf("update entry: %w", err)
	}
	n, err := res.RowsAffected()
	if err != nil {
		return Entry{}, fmt.Errorf("update entry: %w", err)
	}
	if n == 0 {
		return Entry{}, ErrEntryNotFound
	}
	return FindEntryByID(db, id)
}

// DeleteEntry hard-deletes one entry. Returns ErrEntryNotFound if no row
// matched. Per spec there is no soft delete; this tears the row out.
// Ratings against the same title remain — they reference title_id, not
// library_entry.id.
func DeleteEntry(db *sql.DB, id string) error {
	res, err := db.Exec(`DELETE FROM library_entry WHERE id = ?`, id)
	if err != nil {
		return fmt.Errorf("delete entry: %w", err)
	}
	n, err := res.RowsAffected()
	if err != nil {
		return fmt.Errorf("delete entry: %w", err)
	}
	if n == 0 {
		return ErrEntryNotFound
	}
	return nil
}

// isUniqueConstraintErr matches the modernc sqlite driver's UNIQUE error.
func isUniqueConstraintErr(err error) bool {
	return err != nil && strings.Contains(err.Error(), "UNIQUE constraint failed")
}
