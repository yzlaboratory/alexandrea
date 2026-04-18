package httpx

import (
	"net/http"

	"github.com/yzlaboratory/entertainment-library/backend/internal/web"
)

func NewRouter(d Deps) http.Handler {
	mux := http.NewServeMux()
	mux.Handle("GET /api/health", Health())
	if d.Sessions != nil {
		mux.Handle("POST /login", Login(d))
		mux.Handle("POST /logout", Logout(d))
		mux.Handle("GET /api/me", RequireAuth(d, Me(d)))

		if d.TMDB != nil {
			mux.Handle("GET /api/search", RequireAuth(d, Search(d)))
			mux.Handle("GET /api/tmdb/title/{kind}/{id}", RequireAuth(d, TMDBTitle(d)))
			mux.Handle("POST /api/titles", RequireAuth(d, AddTitle(d)))
		}
		mux.Handle("GET /api/titles/{id}", RequireAuth(d, GetTitle(d)))

		mux.Handle("GET /api/library", RequireAuth(d, ListLibrary(d)))
		mux.Handle("POST /api/library", RequireAuth(d, AddLibraryEntry(d)))
		mux.Handle("PATCH /api/library/{id}", RequireAuth(d, UpdateLibraryEntryStatus(d)))
		mux.Handle("DELETE /api/library/{id}", RequireAuth(d, DeleteLibraryEntry(d)))

		mux.Handle("POST /api/library/{id}/rating", RequireAuth(d, UpsertRating(d)))
		mux.Handle("DELETE /api/library/{id}/rating", RequireAuth(d, DeleteRating(d)))
	}

	// Any /api/* request that isn't claimed by an explicit handler returns
	// JSON 404 rather than falling through to the SPA's index.html.
	mux.Handle("/api/", apiNotFound())

	mux.Handle("GET /assets/", web.AssetsHandler())
	mux.Handle("/", web.SPAHandler())

	return CSRFMiddleware(mux, d.CookieSecure)
}

func apiNotFound() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "not found"})
	})
}
