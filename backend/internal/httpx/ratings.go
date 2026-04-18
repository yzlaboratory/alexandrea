package httpx

import (
	"encoding/json"
	"errors"
	"net/http"

	"github.com/yzlaboratory/entertainment-library/backend/internal/library"
)

// maxNoteLen is the spec's one-line note cap plus a little slack for
// Unicode punctuation. Matches specs/03-ratings.md: "single line, ~200
// characters." We reject beyond this rather than silently truncating.
const maxNoteLen = 240

// UpsertRating handles POST /api/library/{id}/rating. Body:
// {"score": 0-5, "note"?: "…"}. The caller's session identifies the rater.
//
// Side effect (per specs/03-ratings.md): if the library_entry isn't
// already `watched`, rating it transitions it to `watched`. That transition
// and the rating row both land in a single transaction so either both
// change or neither does.
//
// Returns the updated Entry (with the new rating embedded) so the frontend
// can reconcile state from one response.
func UpsertRating(d Deps) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sess, ok := SessionFromContext(r.Context())
		if !ok {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "no session"})
			return
		}
		entryID := r.PathValue("id")
		if entryID == "" {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "id is required"})
			return
		}
		var body struct {
			Score *int   `json:"score"`
			Note  string `json:"note"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad json"})
			return
		}
		if body.Score == nil || *body.Score < 0 || *body.Score > 5 {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "score must be 0-5"})
			return
		}
		if len(body.Note) > maxNoteLen {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "note too long"})
			return
		}

		// Resolve the entry so we know the title_id and whether we need
		// the implicit watched transition.
		entry, err := library.FindEntryByID(d.DB, entryID)
		if errors.Is(err, library.ErrEntryNotFound) {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": "entry not found"})
			return
		}
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "lookup failed"})
			return
		}

		if _, err := library.UpsertRating(d.DB, entry.Title.ID, sess.UserID, *body.Score, body.Note); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "upsert failed"})
			return
		}
		if entry.Status != library.StatusWatched {
			if _, err := library.UpdateEntryStatus(d.DB, entry.ID, library.StatusWatched); err != nil {
				writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "status transition failed"})
				return
			}
		}

		// Re-read so ratings + (possibly new) status are both fresh.
		final, err := library.FindEntryByID(d.DB, entryID)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "reload failed"})
			return
		}
		writeJSON(w, http.StatusOK, final)
	})
}

// DeleteRating handles DELETE /api/library/{id}/rating. Removes the
// authenticated user's rating for the entry's title. 404 if no matching
// rating exists — deletes are scoped to the caller, so one user cannot
// remove the other's rating.
func DeleteRating(d Deps) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sess, ok := SessionFromContext(r.Context())
		if !ok {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "no session"})
			return
		}
		entryID := r.PathValue("id")
		if entryID == "" {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "id is required"})
			return
		}

		entry, err := library.FindEntryByID(d.DB, entryID)
		if errors.Is(err, library.ErrEntryNotFound) {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": "entry not found"})
			return
		}
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "lookup failed"})
			return
		}

		if err := library.DeleteRating(d.DB, entry.Title.ID, sess.UserID); err != nil {
			if errors.Is(err, library.ErrRatingNotFound) {
				writeJSON(w, http.StatusNotFound, map[string]string{"error": "rating not found"})
				return
			}
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "delete failed"})
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})
}
