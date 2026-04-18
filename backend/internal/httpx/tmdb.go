package httpx

import (
	"errors"
	"net/http"
	"strconv"
	"strings"

	"github.com/yzlaboratory/entertainment-library/backend/internal/tmdb"
)

// Search handles GET /api/search?q=<query>. Must be wrapped in RequireAuth.
// Returns {"results": [Result, ...]} — shaped identically to what the
// frontend will cache in local title rows when the user adds one (see
// ADR 0004).
func Search(d Deps) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if d.TMDB == nil {
			writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "tmdb disabled"})
			return
		}
		query := strings.TrimSpace(r.URL.Query().Get("q"))
		if query == "" {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "q is required"})
			return
		}

		results, err := d.TMDB.Search(r.Context(), query)
		if err != nil {
			writeTMDBError(w, err)
			return
		}
		// Ensure we return `results: []` rather than `results: null` when
		// TMDB had nothing useful after the person-filter.
		if results == nil {
			results = []tmdb.Result{}
		}
		writeJSON(w, http.StatusOK, map[string]any{"results": results})
	})
}

// TMDBTitle handles GET /api/tmdb/title/:kind/:id. :kind is "movie" or
// "series"; the path segment is validated here before it reaches the client.
// Must be wrapped in RequireAuth.
func TMDBTitle(d Deps) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if d.TMDB == nil {
			writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "tmdb disabled"})
			return
		}
		kindStr := r.PathValue("kind")
		idStr := r.PathValue("id")

		var kind tmdb.Kind
		switch kindStr {
		case "movie":
			kind = tmdb.KindMovie
		case "series":
			kind = tmdb.KindSeries
		default:
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "kind must be movie or series"})
			return
		}

		id, err := strconv.Atoi(idStr)
		if err != nil || id <= 0 {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "id must be a positive integer"})
			return
		}

		result, err := d.TMDB.Get(r.Context(), kind, id)
		if err != nil {
			writeTMDBError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, result)
	})
}

// writeTMDBError maps tmdb package errors to HTTP responses. ErrNotFound
// becomes 404; *ErrUpstream is surfaced as 502 (TMDB is a third party we
// don't control — not our server's fault); everything else is 500.
func writeTMDBError(w http.ResponseWriter, err error) {
	if errors.Is(err, tmdb.ErrNotFound) {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "not found"})
		return
	}
	var upstream *tmdb.ErrUpstream
	if errors.As(err, &upstream) {
		writeJSON(w, http.StatusBadGateway, map[string]string{"error": "tmdb upstream"})
		return
	}
	writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "server error"})
}
