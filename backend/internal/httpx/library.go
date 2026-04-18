package httpx

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"

	"github.com/yzlaboratory/entertainment-library/backend/internal/library"
)

// ListLibrary handles GET /api/library?status=want,watching. The status
// query is comma-separated; omitting it defaults to want+watching, matching
// the "what's on our plate right now" view from specs/02-library.md.
func ListLibrary(d Deps) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw := strings.TrimSpace(r.URL.Query().Get("status"))
		var filters []library.Status
		if raw == "" {
			filters = []library.Status{library.StatusWant, library.StatusWatching}
		} else {
			for _, part := range strings.Split(raw, ",") {
				trimmed := strings.TrimSpace(part)
				if trimmed == "" {
					continue
				}
				s := library.Status(trimmed)
				if !s.IsValid() {
					writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid status filter"})
					return
				}
				filters = append(filters, s)
			}
		}

		entries, err := library.ListEntries(d.DB, filters)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "list failed"})
			return
		}
		if entries == nil {
			entries = []library.Entry{}
		}
		writeJSON(w, http.StatusOK, map[string]any{"entries": entries})
	})
}

// AddLibraryEntry handles POST /api/library. Body: {"title_id": "...",
// "status": "want"} (status optional, defaults to "want"). Returns 201 with
// the created Entry, 404 if title_id does not exist locally, or 409 if a
// row already exists for this title (library_entry.title_id is UNIQUE).
func AddLibraryEntry(d Deps) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			TitleID string `json:"title_id"`
			Status  string `json:"status"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad json"})
			return
		}
		if body.TitleID == "" {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "title_id is required"})
			return
		}
		if body.Status != "" && !library.Status(body.Status).IsValid() {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid status"})
			return
		}

		// Confirm the title actually exists so we can return a precise 404
		// rather than a generic 500 from a foreign-key failure.
		if _, err := library.FindTitleByID(d.DB, body.TitleID); err != nil {
			if errors.Is(err, library.ErrTitleNotFound) {
				writeJSON(w, http.StatusNotFound, map[string]string{"error": "title not found"})
				return
			}
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "lookup failed"})
			return
		}

		entry, err := library.InsertEntry(d.DB, body.TitleID, library.Status(body.Status))
		if errors.Is(err, library.ErrEntryAlreadyExists) {
			writeJSON(w, http.StatusConflict, map[string]string{"error": "title already in library"})
			return
		}
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "insert failed"})
			return
		}
		writeJSON(w, http.StatusCreated, entry)
	})
}

// UpdateLibraryEntryStatus handles PATCH /api/library/{id}. Body:
// {"status": "watching"}. Status is required and must be valid.
func UpdateLibraryEntryStatus(d Deps) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")
		if id == "" {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "id is required"})
			return
		}
		var body struct {
			Status string `json:"status"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad json"})
			return
		}
		s := library.Status(body.Status)
		if !s.IsValid() {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid status"})
			return
		}

		entry, err := library.UpdateEntryStatus(d.DB, id, s)
		if errors.Is(err, library.ErrEntryNotFound) {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": "not found"})
			return
		}
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "update failed"})
			return
		}
		writeJSON(w, http.StatusOK, entry)
	})
}

// DeleteLibraryEntry handles DELETE /api/library/{id}. Hard delete (no
// soft delete), per spec. Returns 204 on success, 404 if no row matched.
func DeleteLibraryEntry(d Deps) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")
		if id == "" {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "id is required"})
			return
		}
		err := library.DeleteEntry(d.DB, id)
		if errors.Is(err, library.ErrEntryNotFound) {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": "not found"})
			return
		}
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "delete failed"})
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})
}
