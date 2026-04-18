package httpx

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestRouter_HealthGETReturns200(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/api/health", nil)
	rec := httptest.NewRecorder()

	NewRouter(Deps{}).ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusOK)
	}
}

func TestRouter_POSTToGETOnlyAPIPathReturns404JSON(t *testing.T) {
	// POST hits the CSRF middleware first; with a matching token the request
	// flows through to the mux. The `/api/` catch-all claims wrong-method
	// API requests, returning JSON 404 rather than an HTML 405 — the SPA's
	// JSON error shape is uniform across all /api/* misses.
	req := httptest.NewRequest(http.MethodPost, "/api/health", nil)
	req.AddCookie(&http.Cookie{Name: csrfCookieName, Value: "matching"})
	req.Header.Set(csrfHeaderName, "matching")
	rec := httptest.NewRecorder()

	NewRouter(Deps{}).ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusNotFound)
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
		t.Fatalf("content-type = %q, want application/json", ct)
	}
}

func TestRouter_POSTWithoutCSRFReturns403(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "/api/health", nil)
	rec := httptest.NewRecorder()

	NewRouter(Deps{}).ServeHTTP(rec, req)

	if rec.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusForbidden)
	}
}

func TestRouter_UnknownAPIPathReturnsJSON404(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/api/does-not-exist", nil)
	rec := httptest.NewRecorder()

	NewRouter(Deps{}).ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusNotFound)
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
		t.Fatalf("content-type = %q, want application/json", ct)
	}
	var body map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode body: %v", err)
	}
	if body["error"] == "" {
		t.Fatalf("missing error field: %v", body)
	}
}

func TestRouter_NonAPIPathFallsThroughToSPAHandler(t *testing.T) {
	// Without a built frontend (only dist/.gitkeep present in the repo),
	// the SPA handler responds with 503 via notBuiltHandler. What matters
	// for the router is that the request isn't a 404 — it's been routed
	// to the SPA, not rejected as an unknown path.
	req := httptest.NewRequest(http.MethodGet, "/library", nil)
	rec := httptest.NewRecorder()

	NewRouter(Deps{}).ServeHTTP(rec, req)

	if rec.Code == http.StatusNotFound {
		t.Fatalf("unexpected 404; SPA handler should have claimed the path")
	}
}
