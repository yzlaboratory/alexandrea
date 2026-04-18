package library

import (
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
)

// Rating is one row of the rating table joined with the rater's display name.
// A title has 0, 1, or 2 ratings — one per user — enforced by the schema's
// UNIQUE(title_id, user_id).
type Rating struct {
	ID          string    `json:"id"`
	TitleID     string    `json:"title_id"`
	UserID      string    `json:"user_id"`
	DisplayName string    `json:"display_name"`
	Score       int       `json:"score"`
	Note        string    `json:"note,omitempty"`
	RatedAt     time.Time `json:"rated_at"`
}

// ErrRatingNotFound is returned when a rating lookup or delete misses.
var ErrRatingNotFound = errors.New("library: rating not found")

// UpsertRating creates or overwrites the rating for (titleID, userID). The
// schema enforces score ∈ [0, 5]; callers are expected to validate before
// reaching here so the error path surfaces as 400, not 500.
//
// Spec (03-ratings.md): rating a non-watched entry implicitly transitions
// it to `watched`. That transition is a caller concern — this function
// only touches the rating row so the data layer stays compositional.
func UpsertRating(db *sql.DB, titleID, userID string, score int, note string) (Rating, error) {
	if titleID == "" || userID == "" {
		return Rating{}, errors.New("library: title_id and user_id are required")
	}
	if score < 0 || score > 5 {
		return Rating{}, fmt.Errorf("library: score %d out of range [0, 5]", score)
	}

	// ON CONFLICT overwrites score/note/rated_at in place — edits do not
	// keep history (spec: no audit log, no rewatch history).
	id := uuid.NewString()
	noteArg := sqlNullable(note)
	_, err := db.Exec(
		`INSERT INTO rating (id, title_id, user_id, score, note, rated_at)
		 VALUES (?, ?, ?, ?, ?, ?)
		 ON CONFLICT(title_id, user_id) DO UPDATE
		   SET score = excluded.score,
		       note = excluded.note,
		       rated_at = excluded.rated_at`,
		id, titleID, userID, score, noteArg, formatTimestamp(time.Now().UTC()),
	)
	if err != nil {
		return Rating{}, fmt.Errorf("upsert rating: %w", err)
	}
	return findRatingByTitleUser(db, titleID, userID)
}

// DeleteRating removes the rating for (titleID, userID). Returns
// ErrRatingNotFound when no row matched — callers can treat that as 404.
func DeleteRating(db *sql.DB, titleID, userID string) error {
	res, err := db.Exec(`DELETE FROM rating WHERE title_id = ? AND user_id = ?`, titleID, userID)
	if err != nil {
		return fmt.Errorf("delete rating: %w", err)
	}
	n, err := res.RowsAffected()
	if err != nil {
		return fmt.Errorf("delete rating: %w", err)
	}
	if n == 0 {
		return ErrRatingNotFound
	}
	return nil
}

// ListRatingsByTitleIDs returns every rating for the given titles, joined
// against the user table for the display name. Order is (title_id, user_id)
// so callers can stitch deterministically.
func ListRatingsByTitleIDs(db *sql.DB, titleIDs []string) ([]Rating, error) {
	if len(titleIDs) == 0 {
		return nil, nil
	}
	placeholders := make([]string, len(titleIDs))
	args := make([]any, len(titleIDs))
	for i, id := range titleIDs {
		placeholders[i] = "?"
		args[i] = id
	}

	q := `SELECT r.id, r.title_id, r.user_id, u.display_name, r.score,
	             COALESCE(r.note, ''), r.rated_at
	        FROM rating r
	        JOIN "user" u ON u.id = r.user_id
	       WHERE r.title_id IN (` + strings.Join(placeholders, ",") + `)
	    ORDER BY r.title_id, r.user_id`

	rows, err := db.Query(q, args...)
	if err != nil {
		return nil, fmt.Errorf("list ratings: %w", err)
	}
	defer rows.Close()

	var out []Rating
	for rows.Next() {
		var r Rating
		var ratedAt string
		if err := rows.Scan(&r.ID, &r.TitleID, &r.UserID, &r.DisplayName, &r.Score, &r.Note, &ratedAt); err != nil {
			return nil, fmt.Errorf("scan rating: %w", err)
		}
		if r.RatedAt, err = parseTimestamp(ratedAt); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// findRatingByTitleUser is the single-rating lookup used internally after
// UpsertRating to return the rendered (joined) row.
func findRatingByTitleUser(db *sql.DB, titleID, userID string) (Rating, error) {
	const q = `SELECT r.id, r.title_id, r.user_id, u.display_name, r.score,
	                  COALESCE(r.note, ''), r.rated_at
	             FROM rating r
	             JOIN "user" u ON u.id = r.user_id
	            WHERE r.title_id = ? AND r.user_id = ?`
	var r Rating
	var ratedAt string
	err := db.QueryRow(q, titleID, userID).Scan(&r.ID, &r.TitleID, &r.UserID, &r.DisplayName, &r.Score, &r.Note, &ratedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return Rating{}, ErrRatingNotFound
	}
	if err != nil {
		return Rating{}, fmt.Errorf("find rating: %w", err)
	}
	if r.RatedAt, err = parseTimestamp(ratedAt); err != nil {
		return Rating{}, err
	}
	return r, nil
}
