// Package web embeds the built frontend SPA and serves it.
//
// The embed directive below requires frontend/dist to have been copied to
// backend/internal/web/dist before `go build` runs. The project Makefile
// handles this — see `make build`. If only the placeholder .gitkeep is
// present, the handler returns a 503 so the missing-build case is loud.
package web

import (
	"embed"
	"errors"
	"io/fs"
	"net/http"
	"path"
	"strings"
)

//go:embed all:dist
var distFS embed.FS

const (
	cacheImmutable = "public, max-age=31536000, immutable"
	cacheNoCache   = "no-cache"
)

// distRoot returns the dist subtree with "dist/" stripped, or an error if the
// frontend has not been built.
func distRoot() (fs.FS, error) {
	sub, err := fs.Sub(distFS, "dist")
	if err != nil {
		return nil, err
	}
	// Confirm the sentinel file is present; otherwise we only have .gitkeep.
	if _, err := fs.Stat(sub, "index.html"); err != nil {
		return nil, err
	}
	return sub, nil
}

// AssetsHandler serves hashed bundles under /assets/ with an immutable cache.
// The cache header is set on every response (including 404s) so clients never
// cache a missing asset as a non-immutable resource.
func AssetsHandler() http.Handler {
	root, err := distRoot()
	if err != nil {
		return notBuiltHandler()
	}
	fileServer := http.FileServer(http.FS(root))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		name := strings.TrimPrefix(path.Clean("/"+strings.TrimPrefix(r.URL.Path, "/")), "/")
		info, err := fs.Stat(root, name)
		if errors.Is(err, fs.ErrNotExist) || (err == nil && info.IsDir()) {
			w.Header().Set("Cache-Control", cacheImmutable)
			http.NotFound(w, r)
			return
		}
		if err != nil {
			http.Error(w, "asset lookup", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Cache-Control", cacheImmutable)
		fileServer.ServeHTTP(w, r)
	})
}

// SPAHandler serves index.html (no-cache) as the catch-all for non-API routes,
// falling through to embedded static files (favicon, icons, etc.) when they
// exist. Missing static files collapse into the SPA fallback so deep links
// rendered by React Router work.
func SPAHandler() http.Handler {
	root, err := distRoot()
	if err != nil {
		return notBuiltHandler()
	}
	indexBytes, err := fs.ReadFile(root, "index.html")
	if err != nil {
		return notBuiltHandler()
	}
	fileServer := http.FileServer(http.FS(root))

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		clean := path.Clean("/" + strings.TrimPrefix(r.URL.Path, "/"))
		if clean == "/" {
			serveIndex(w, indexBytes)
			return
		}
		name := strings.TrimPrefix(clean, "/")
		info, err := fs.Stat(root, name)
		switch {
		case err == nil && !info.IsDir():
			fileServer.ServeHTTP(w, r)
		case err == nil && info.IsDir(), errors.Is(err, fs.ErrNotExist):
			serveIndex(w, indexBytes)
		default:
			http.Error(w, "static lookup", http.StatusInternalServerError)
		}
	})
}

func serveIndex(w http.ResponseWriter, body []byte) {
	w.Header().Set("Cache-Control", cacheNoCache)
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(body)
}

func notBuiltHandler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "frontend not built — run `make build`", http.StatusServiceUnavailable)
	})
}
