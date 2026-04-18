package httpx

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/yzlaboratory/entertainment-library/backend/internal/auth"
	"github.com/yzlaboratory/entertainment-library/backend/internal/storage"
)

func testDeps(t *testing.T) (Deps, *sql.DB) {
	t.Helper()
	dsn := filepath.Join(t.TempDir(), "t.sqlite")
	db, err := storage.Open(dsn)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })
	if err := storage.Migrate(db); err != nil {
		t.Fatalf("migrate: %v", err)
	}

	hash, err := auth.HashPassword("correcthorsebatterystaple")
	if err != nil {
		t.Fatalf("hash: %v", err)
	}
	if _, err := db.Exec(`INSERT INTO "user" (id, display_name) VALUES (?, ?)`, "kira", "Kira"); err != nil {
		t.Fatalf("insert user: %v", err)
	}
	if _, err := db.Exec(
		`INSERT INTO user_credential (user_id, password_hash, updated_at) VALUES (?, ?, ?)`,
		"kira", hash, time.Now().UTC().Format(time.RFC3339Nano),
	); err != nil {
		t.Fatalf("insert credential: %v", err)
	}

	return Deps{DB: db, Sessions: auth.NewSessionStore(db), CookieSecure: false}, db
}

func loginForm(username, password string) *http.Request {
	form := url.Values{"username": {username}, "password": {password}}
	req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	return req
}

func TestLogin_Success_SetsSessionCookie(t *testing.T) {
	deps, _ := testDeps(t)

	rec := httptest.NewRecorder()
	Login(deps).ServeHTTP(rec, loginForm("kira", "correcthorsebatterystaple"))

	if rec.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want %d (body %q)", rec.Code, http.StatusNoContent, rec.Body.String())
	}
	cookie := firstCookie(rec, sessionCookieName)
	if cookie == nil {
		t.Fatal("no session cookie set")
	}
	if !cookie.HttpOnly {
		t.Error("session cookie must be HttpOnly")
	}
	if cookie.SameSite != http.SameSiteLaxMode {
		t.Errorf("SameSite = %v, want Lax", cookie.SameSite)
	}
	if cookie.MaxAge <= 0 {
		t.Errorf("MaxAge = %d, want > 0", cookie.MaxAge)
	}
}

func TestLogin_WrongPassword_Returns401NoCookie(t *testing.T) {
	deps, _ := testDeps(t)

	rec := httptest.NewRecorder()
	Login(deps).ServeHTTP(rec, loginForm("kira", "wrong"))

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", rec.Code)
	}
	if c := firstCookie(rec, sessionCookieName); c != nil {
		t.Errorf("unexpected cookie set on failed login: %q", c.Value)
	}
}

func TestLogin_UnknownUser_Returns401NoCookie(t *testing.T) {
	deps, _ := testDeps(t)

	rec := httptest.NewRecorder()
	Login(deps).ServeHTTP(rec, loginForm("ghost", "anything"))

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", rec.Code)
	}
}

func TestLogin_MissingFields_Returns401(t *testing.T) {
	deps, _ := testDeps(t)

	for _, f := range []url.Values{
		{"username": {"kira"}},
		{"password": {"x"}},
		{},
	} {
		req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(f.Encode()))
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		rec := httptest.NewRecorder()
		Login(deps).ServeHTTP(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Errorf("form %v: status = %d, want 401", f, rec.Code)
		}
	}
}

func TestLogin_SecureFlagFollowsDeps(t *testing.T) {
	deps, _ := testDeps(t)
	deps.CookieSecure = true

	rec := httptest.NewRecorder()
	Login(deps).ServeHTTP(rec, loginForm("kira", "correcthorsebatterystaple"))
	c := firstCookie(rec, sessionCookieName)
	if c == nil || !c.Secure {
		t.Errorf("expected Secure cookie; got %#v", c)
	}
}

func TestLogout_ClearsCookieAndDeletesSession(t *testing.T) {
	deps, db := testDeps(t)

	rec := httptest.NewRecorder()
	Login(deps).ServeHTTP(rec, loginForm("kira", "correcthorsebatterystaple"))
	sessCookie := firstCookie(rec, sessionCookieName)
	if sessCookie == nil {
		t.Fatal("login did not set cookie")
	}

	req := httptest.NewRequest(http.MethodPost, "/logout", nil)
	req.AddCookie(sessCookie)
	out := httptest.NewRecorder()
	Logout(deps).ServeHTTP(out, req)

	if out.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want 204", out.Code)
	}
	cleared := firstCookie(out, sessionCookieName)
	if cleared == nil || cleared.MaxAge >= 0 {
		t.Errorf("expected cookie cleared (MaxAge < 0), got %#v", cleared)
	}

	var count int
	if err := db.QueryRow(`SELECT COUNT(*) FROM session WHERE id = ?`, sessCookie.Value).Scan(&count); err != nil {
		t.Fatalf("count: %v", err)
	}
	if count != 0 {
		t.Errorf("session row remains after logout: count=%d", count)
	}
}

func TestLogout_NoCookieIsIdempotent(t *testing.T) {
	deps, _ := testDeps(t)
	req := httptest.NewRequest(http.MethodPost, "/logout", nil)
	rec := httptest.NewRecorder()
	Logout(deps).ServeHTTP(rec, req)
	if rec.Code != http.StatusNoContent {
		t.Errorf("status = %d, want 204", rec.Code)
	}
}

func TestMe_AuthenticatedReturnsProfile(t *testing.T) {
	deps, _ := testDeps(t)
	login := httptest.NewRecorder()
	Login(deps).ServeHTTP(login, loginForm("kira", "correcthorsebatterystaple"))
	sessCookie := firstCookie(login, sessionCookieName)
	if sessCookie == nil {
		t.Fatal("login did not set cookie")
	}

	req := httptest.NewRequest(http.MethodGet, "/api/me", nil)
	req.AddCookie(sessCookie)
	rec := httptest.NewRecorder()
	Me(deps).ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body=%q", rec.Code, rec.Body.String())
	}
	var body map[string]string
	if err := json.NewDecoder(rec.Body).Decode(&body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if body["user_id"] != "kira" || body["display_name"] != "Kira" {
		t.Errorf("me body = %v, want user_id=kira display_name=Kira", body)
	}
}

func TestMe_WithoutCookieReturns401(t *testing.T) {
	deps, _ := testDeps(t)
	req := httptest.NewRequest(http.MethodGet, "/api/me", nil)
	rec := httptest.NewRecorder()
	Me(deps).ServeHTTP(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", rec.Code)
	}
}

func TestMe_UnknownSessionReturns401(t *testing.T) {
	deps, _ := testDeps(t)
	req := httptest.NewRequest(http.MethodGet, "/api/me", nil)
	req.AddCookie(&http.Cookie{Name: sessionCookieName, Value: "bogus"})
	rec := httptest.NewRecorder()
	Me(deps).ServeHTTP(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", rec.Code)
	}
}

func TestRouter_IncludesAuthRoutesWhenSessionsProvided(t *testing.T) {
	deps, _ := testDeps(t)
	router := NewRouter(deps)

	// /login accepts POST only
	req := httptest.NewRequest(http.MethodGet, "/login", nil)
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)
	if rec.Code != http.StatusMethodNotAllowed {
		t.Errorf("GET /login status = %d, want 405", rec.Code)
	}

	req = httptest.NewRequest(http.MethodGet, "/api/me", nil)
	rec = httptest.NewRecorder()
	router.ServeHTTP(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Errorf("GET /api/me without cookie = %d, want 401", rec.Code)
	}
}

func firstCookie(rec *httptest.ResponseRecorder, name string) *http.Cookie {
	for _, c := range rec.Result().Cookies() {
		if c.Name == name {
			return c
		}
	}
	return nil
}
