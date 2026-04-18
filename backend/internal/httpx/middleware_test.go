package httpx

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// A tiny sentinel handler used to observe that RequireAuth let the request
// through and populated the session context.
func okSentinel(t *testing.T) http.Handler {
	t.Helper()
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sess, ok := SessionFromContext(r.Context())
		if !ok {
			t.Errorf("SessionFromContext returned !ok inside the protected handler")
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		if sess.UserID == "" {
			t.Errorf("session user id is empty")
		}
		w.WriteHeader(http.StatusOK)
	})
}

func TestRequireAuth_NoCookieReturns401(t *testing.T) {
	d, _ := testDeps(t)
	h := RequireAuth(d, okSentinel(t))

	req := httptest.NewRequest(http.MethodGet, "/whatever", nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", rec.Code)
	}
}

func TestRequireAuth_UnknownSessionReturns401(t *testing.T) {
	d, _ := testDeps(t)
	h := RequireAuth(d, okSentinel(t))

	req := httptest.NewRequest(http.MethodGet, "/whatever", nil)
	req.AddCookie(&http.Cookie{Name: sessionCookieName, Value: "not-a-real-session"})
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", rec.Code)
	}
}

func TestRequireAuth_ValidSessionPassesThroughAndPopulatesContext(t *testing.T) {
	d, _ := testDeps(t)
	sess, err := d.Sessions.Mint("kira")
	if err != nil {
		t.Fatalf("mint: %v", err)
	}

	h := RequireAuth(d, okSentinel(t))

	req := httptest.NewRequest(http.MethodGet, "/whatever", nil)
	req.AddCookie(&http.Cookie{Name: sessionCookieName, Value: sess.ID})
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
}

func TestSessionFromContext_ReturnsFalseWhenMiddlewareMissing(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/whatever", nil)
	if _, ok := SessionFromContext(req.Context()); ok {
		t.Fatal("SessionFromContext ok=true with no middleware; want false")
	}
}
