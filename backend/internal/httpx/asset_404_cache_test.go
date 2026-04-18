package httpx

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// Regression: a missing /assets/* request must still carry the immutable
// Cache-Control header when it passes through the full router (CSRF middleware
// + mux + asset handler), not only when the asset handler is called in
// isolation.
func TestRouter_MissingAssetStillCarriesImmutableCacheHeader(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/assets/does-not-exist.js", nil)
	req.AddCookie(&http.Cookie{Name: csrfCookieName, Value: "dummy"})
	rec := httptest.NewRecorder()

	NewRouter(Deps{}).ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusNotFound)
	}
	const want = "public, max-age=31536000, immutable"
	if got := rec.Header().Get("Cache-Control"); got != want {
		t.Fatalf("cache-control = %q, want %q", got, want)
	}
}
