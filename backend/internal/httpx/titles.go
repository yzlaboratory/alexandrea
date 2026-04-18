package httpx

import (
	"encoding/json"
	"errors"
	"net/http"

	"github.com/yzlaboratory/entertainment-library/backend/internal/library"
	"github.com/yzlaboratory/entertainment-library/backend/internal/tmdb"
)

// AddTitle handles POST /api/titles. Body: {"tmdb_id": int, "kind": "movie"|"series"}.
//
// Idempotent — if a row with the same tmdb_id already exists, it is returned
// with 200 OK; on first add the row is fetched from TMDB, persisted, and
// returned with 201 Created.
//
// Must be wrapped in RequireAuth.
func AddTitle(d Deps) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if d.TMDB == nil {
			writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "tmdb disabled"})
			return
		}
		var body struct {
			TMDBID int    `json:"tmdb_id"`
			Kind   string `json:"kind"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad json"})
			return
		}
		if body.TMDBID <= 0 {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "tmdb_id is required"})
			return
		}
		var kind tmdb.Kind
		switch body.Kind {
		case "movie":
			kind = tmdb.KindMovie
		case "series":
			kind = tmdb.KindSeries
		default:
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "kind must be movie or series"})
			return
		}

		// Idempotency: if we already have a row for this tmdb_id, return it.
		if existing, err := library.FindTitleByTMDBID(d.DB, body.TMDBID); err == nil {
			writeJSON(w, http.StatusOK, existing)
			return
		} else if !errors.Is(err, library.ErrTitleNotFound) {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "lookup failed"})
			return
		}

		// First add — fetch metadata from TMDB.
		meta, err := d.TMDB.Get(r.Context(), kind, body.TMDBID)
		if err != nil {
			writeTMDBError(w, err)
			return
		}

		t, err := library.InsertTitle(d.DB, library.Title{
			TMDBID:       meta.TMDBID,
			Kind:         library.Kind(meta.Kind),
			DisplayTitle: meta.Title,
			ReleaseYear:  meta.Year,
			Synopsis:     meta.Overview,
			PosterPath:   meta.PosterPath,
		})
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "insert failed"})
			return
		}
		writeJSON(w, http.StatusCreated, t)
	})
}

// GetTitle handles GET /api/titles/{id}. Reads the local title row only —
// never calls TMDB at request time (per ADR 0004).
//
// Must be wrapped in RequireAuth.
func GetTitle(d Deps) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")
		if id == "" {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "id is required"})
			return
		}
		t, err := library.FindTitleByID(d.DB, id)
		if errors.Is(err, library.ErrTitleNotFound) {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": "not found"})
			return
		}
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "lookup failed"})
			return
		}
		writeJSON(w, http.StatusOK, t)
	})
}
