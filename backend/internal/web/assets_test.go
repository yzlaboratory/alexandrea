package web

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// These tests run against the package's embed.FS. If only dist/.gitkeep is
// present (the common repo state), both handlers return 503. After
// `make build` — which copies frontend/dist into backend/internal/web/dist —
// the handlers serve the built SPA. We exercise both shapes.

func TestAssetsHandler_RespondsWithExpectedStatus(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/assets/unknown.js", nil)
	rec := httptest.NewRecorder()

	AssetsHandler().ServeHTTP(rec, req)

	if built := FrontendBuilt(); built {
		// Unknown asset under a real build should 404, not 500, and still
		// wear the immutable cache header because the handler sets it
		// before delegating.
		if rec.Code != http.StatusNotFound {
			t.Fatalf("status = %d, want %d", rec.Code, http.StatusNotFound)
		}
		if got := rec.Header().Get("Cache-Control"); got != cacheImmutable {
			t.Fatalf("cache-control = %q, want %q", got, cacheImmutable)
		}
	} else {
		if rec.Code != http.StatusServiceUnavailable {
			t.Fatalf("status = %d, want %d (frontend not built)", rec.Code, http.StatusServiceUnavailable)
		}
	}
}

func TestSPAHandler_ServesIndexOrReports503(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	rec := httptest.NewRecorder()

	SPAHandler().ServeHTTP(rec, req)

	if FrontendBuilt() {
		if rec.Code != http.StatusOK {
			t.Fatalf("status = %d, want %d", rec.Code, http.StatusOK)
		}
		if got := rec.Header().Get("Cache-Control"); got != cacheNoCache {
			t.Fatalf("cache-control = %q, want %q", got, cacheNoCache)
		}
		if got := rec.Header().Get("Content-Type"); got == "" {
			t.Fatalf("missing Content-Type")
		}
	} else {
		if rec.Code != http.StatusServiceUnavailable {
			t.Fatalf("status = %d, want %d", rec.Code, http.StatusServiceUnavailable)
		}
	}
}

func TestSPAHandler_DeepLinkFallsBackToIndex(t *testing.T) {
	if !FrontendBuilt() {
		t.Skip("requires built frontend; run `make build`")
	}
	req := httptest.NewRequest(http.MethodGet, "/library/some-title", nil)
	rec := httptest.NewRecorder()

	SPAHandler().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusOK)
	}
	if got := rec.Header().Get("Cache-Control"); got != cacheNoCache {
		t.Fatalf("deep-link cache-control = %q, want %q", got, cacheNoCache)
	}
}

func TestSPAHandler_StaticFileServedWhenPresent(t *testing.T) {
	if !FrontendBuilt() {
		t.Skip("requires built frontend; run `make build`")
	}
	req := httptest.NewRequest(http.MethodGet, "/favicon.svg", nil)
	rec := httptest.NewRecorder()

	SPAHandler().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusOK)
	}
	// Static files inherit FileServer's default cache (no explicit header set here).
	if got := rec.Header().Get("Cache-Control"); got == cacheNoCache {
		t.Fatalf("static file unexpectedly carries SPA no-cache header")
	}
}

