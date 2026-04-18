package httpx

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func nextOK() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
}

func TestCSRF_GETMintsCookieOnFirstRequest(t *testing.T) {
	h := CSRFMiddleware(nextOK(), false)

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/any", nil))

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d", rec.Code)
	}
	c := firstCookie(rec, csrfCookieName)
	if c == nil {
		t.Fatal("CSRF cookie not set on first GET")
	}
	if c.HttpOnly {
		t.Error("CSRF cookie must NOT be HttpOnly (SPA reads it)")
	}
	if c.Value == "" {
		t.Error("empty CSRF token")
	}
}

func TestCSRF_GETReusesExistingCookie(t *testing.T) {
	h := CSRFMiddleware(nextOK(), false)

	req := httptest.NewRequest(http.MethodGet, "/any", nil)
	req.AddCookie(&http.Cookie{Name: csrfCookieName, Value: "pre-existing"})
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if c := firstCookie(rec, csrfCookieName); c != nil {
		t.Errorf("unexpected new cookie set when one existed: %q", c.Value)
	}
}

func TestCSRF_POSTWithoutTokenRejected(t *testing.T) {
	h := CSRFMiddleware(nextOK(), false)

	req := httptest.NewRequest(http.MethodPost, "/mutate", strings.NewReader(""))
	req.AddCookie(&http.Cookie{Name: csrfCookieName, Value: "known-token"})
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusForbidden {
		t.Errorf("status = %d, want 403", rec.Code)
	}
}

func TestCSRF_POSTWithWrongTokenRejected(t *testing.T) {
	h := CSRFMiddleware(nextOK(), false)

	req := httptest.NewRequest(http.MethodPost, "/mutate", strings.NewReader(""))
	req.AddCookie(&http.Cookie{Name: csrfCookieName, Value: "known-token"})
	req.Header.Set(csrfHeaderName, "wrong-token")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusForbidden {
		t.Errorf("status = %d, want 403", rec.Code)
	}
}

func TestCSRF_POSTWithMatchingTokenPasses(t *testing.T) {
	h := CSRFMiddleware(nextOK(), false)

	req := httptest.NewRequest(http.MethodPost, "/mutate", strings.NewReader(""))
	req.AddCookie(&http.Cookie{Name: csrfCookieName, Value: "matching"})
	req.Header.Set(csrfHeaderName, "matching")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("status = %d, want 200 (body %q)", rec.Code, rec.Body.String())
	}
}

func TestCSRF_POSTWithoutCookieRejected(t *testing.T) {
	// Without a cookie, the middleware mints a fresh one — the attacker's
	// request has no way to guess it, so the compare fails.
	h := CSRFMiddleware(nextOK(), false)

	req := httptest.NewRequest(http.MethodPost, "/mutate", strings.NewReader(""))
	req.Header.Set(csrfHeaderName, "guessed")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusForbidden {
		t.Errorf("status = %d, want 403", rec.Code)
	}
}

func TestCSRF_MethodsAreProtected(t *testing.T) {
	for _, m := range []string{http.MethodPut, http.MethodPatch, http.MethodDelete} {
		t.Run(m, func(t *testing.T) {
			h := CSRFMiddleware(nextOK(), false)
			req := httptest.NewRequest(m, "/mutate", nil)
			req.AddCookie(&http.Cookie{Name: csrfCookieName, Value: "x"})
			rec := httptest.NewRecorder()
			h.ServeHTTP(rec, req)
			if rec.Code != http.StatusForbidden {
				t.Errorf("%s without matching header = %d, want 403", m, rec.Code)
			}
		})
	}
}

func TestCSRF_SecureFlagFollowsParam(t *testing.T) {
	h := CSRFMiddleware(nextOK(), true)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/", nil))
	c := firstCookie(rec, csrfCookieName)
	if c == nil || !c.Secure {
		t.Errorf("expected Secure cookie; got %#v", c)
	}
}

func TestCSRF_TokensAreRandom(t *testing.T) {
	h := CSRFMiddleware(nextOK(), false)
	seen := map[string]bool{}
	for i := 0; i < 10; i++ {
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/", nil))
		c := firstCookie(rec, csrfCookieName)
		if c == nil {
			t.Fatal("no cookie")
		}
		if seen[c.Value] {
			t.Fatalf("duplicate token: %q", c.Value)
		}
		seen[c.Value] = true
	}
}
