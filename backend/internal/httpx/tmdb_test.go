package httpx

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/yzlaboratory/entertainment-library/backend/internal/tmdb"
)

// fakeTMDB implements TMDBSearcher by delegating to function fields, so each
// test can shape behaviour inline.
type fakeTMDB struct {
	search func(ctx context.Context, query string) ([]tmdb.Result, error)
	get    func(ctx context.Context, kind tmdb.Kind, id int) (tmdb.Result, error)
}

func (f *fakeTMDB) Search(ctx context.Context, query string) ([]tmdb.Result, error) {
	return f.search(ctx, query)
}
func (f *fakeTMDB) Get(ctx context.Context, kind tmdb.Kind, id int) (tmdb.Result, error) {
	return f.get(ctx, kind, id)
}

// authedDeps returns test Deps wired with a minted session cookie value,
// so tests can construct Requests that pass RequireAuth.
func authedDeps(t *testing.T, tm TMDBSearcher) (Deps, string) {
	t.Helper()
	d, _ := testDeps(t)
	d.TMDB = tm
	sess, err := d.Sessions.Mint("kira")
	if err != nil {
		t.Fatalf("mint: %v", err)
	}
	return d, sess.ID
}

func TestSearch_RequiresAuth(t *testing.T) {
	d, _ := testDeps(t)
	d.TMDB = &fakeTMDB{}
	req := httptest.NewRequest(http.MethodGet, "/api/search?q=foo", nil)
	rec := httptest.NewRecorder()
	RequireAuth(d, Search(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", rec.Code)
	}
}

func TestSearch_MissingQueryReturns400(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	req := httptest.NewRequest(http.MethodGet, "/api/search?q=", nil)
	req.AddCookie(&http.Cookie{Name: sessionCookieName, Value: sid})
	rec := httptest.NewRecorder()
	RequireAuth(d, Search(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
}

func TestSearch_HappyPathReturnsResults(t *testing.T) {
	fake := &fakeTMDB{
		search: func(_ context.Context, q string) ([]tmdb.Result, error) {
			if q != "matrix" {
				t.Errorf("forwarded query = %q", q)
			}
			return []tmdb.Result{
				{TMDBID: 1, Kind: tmdb.KindMovie, Title: "The Matrix", Year: 1999},
			}, nil
		},
	}
	d, sid := authedDeps(t, fake)

	req := httptest.NewRequest(http.MethodGet, "/api/search?q=matrix", nil)
	req.AddCookie(&http.Cookie{Name: sessionCookieName, Value: sid})
	rec := httptest.NewRecorder()
	RequireAuth(d, Search(d)).ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d", rec.Code)
	}
	var body struct {
		Results []tmdb.Result `json:"results"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(body.Results) != 1 || body.Results[0].Title != "The Matrix" {
		t.Errorf("unexpected body: %+v", body)
	}
}

func TestSearch_EmptyResultsIsJSONArrayNotNull(t *testing.T) {
	fake := &fakeTMDB{
		search: func(_ context.Context, _ string) ([]tmdb.Result, error) { return nil, nil },
	}
	d, sid := authedDeps(t, fake)

	req := httptest.NewRequest(http.MethodGet, "/api/search?q=whatever", nil)
	req.AddCookie(&http.Cookie{Name: sessionCookieName, Value: sid})
	rec := httptest.NewRecorder()
	RequireAuth(d, Search(d)).ServeHTTP(rec, req)

	if got := rec.Body.String(); !contains(got, `"results":[]`) {
		t.Errorf("body = %s; expected results:[]", got)
	}
}

func TestSearch_UpstreamErrorMapsTo502(t *testing.T) {
	fake := &fakeTMDB{
		search: func(context.Context, string) ([]tmdb.Result, error) {
			return nil, &tmdb.ErrUpstream{Status: 500, Body: "boom"}
		},
	}
	d, sid := authedDeps(t, fake)
	req := httptest.NewRequest(http.MethodGet, "/api/search?q=x", nil)
	req.AddCookie(&http.Cookie{Name: sessionCookieName, Value: sid})
	rec := httptest.NewRecorder()
	RequireAuth(d, Search(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusBadGateway {
		t.Errorf("status = %d, want 502", rec.Code)
	}
}

func TestTMDBTitle_HappyPath(t *testing.T) {
	fake := &fakeTMDB{
		get: func(_ context.Context, kind tmdb.Kind, id int) (tmdb.Result, error) {
			if kind != tmdb.KindMovie || id != 27205 {
				t.Errorf("got kind=%v id=%d", kind, id)
			}
			return tmdb.Result{TMDBID: 27205, Kind: tmdb.KindMovie, Title: "Inception", Year: 2010}, nil
		},
	}
	d, sid := authedDeps(t, fake)
	req := httptest.NewRequest(http.MethodGet, "/api/tmdb/title/movie/27205", nil)
	req.AddCookie(&http.Cookie{Name: sessionCookieName, Value: sid})
	rec := httptest.NewRecorder()
	NewRouter(d).ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body=%s", rec.Code, rec.Body.String())
	}
	var got tmdb.Result
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if got.TMDBID != 27205 || got.Title != "Inception" {
		t.Errorf("got = %+v", got)
	}
}

func TestTMDBTitle_BadKindReturns400(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	req := httptest.NewRequest(http.MethodGet, "/api/tmdb/title/person/1", nil)
	req.AddCookie(&http.Cookie{Name: sessionCookieName, Value: sid})
	rec := httptest.NewRecorder()
	NewRouter(d).ServeHTTP(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("status = %d, want 400", rec.Code)
	}
}

func TestTMDBTitle_BadIDReturns400(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	req := httptest.NewRequest(http.MethodGet, "/api/tmdb/title/movie/0", nil)
	req.AddCookie(&http.Cookie{Name: sessionCookieName, Value: sid})
	rec := httptest.NewRecorder()
	NewRouter(d).ServeHTTP(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("status = %d, want 400", rec.Code)
	}
}

func TestTMDBTitle_UpstreamNotFoundMapsTo404(t *testing.T) {
	fake := &fakeTMDB{
		get: func(context.Context, tmdb.Kind, int) (tmdb.Result, error) {
			return tmdb.Result{}, tmdb.ErrNotFound
		},
	}
	d, sid := authedDeps(t, fake)
	req := httptest.NewRequest(http.MethodGet, "/api/tmdb/title/movie/9999999", nil)
	req.AddCookie(&http.Cookie{Name: sessionCookieName, Value: sid})
	rec := httptest.NewRecorder()
	NewRouter(d).ServeHTTP(rec, req)
	if rec.Code != http.StatusNotFound {
		t.Errorf("status = %d, want 404", rec.Code)
	}
}

func TestSearch_NoTMDBDepReturns503(t *testing.T) {
	d, _ := testDeps(t)
	// d.TMDB intentionally nil.
	sess, err := d.Sessions.Mint("kira")
	if err != nil {
		t.Fatalf("mint: %v", err)
	}
	req := httptest.NewRequest(http.MethodGet, "/api/search?q=x", nil)
	req.AddCookie(&http.Cookie{Name: sessionCookieName, Value: sess.ID})
	rec := httptest.NewRecorder()
	// Call the handler directly because the router skips registration when
	// d.TMDB is nil — we want to assert the handler's own defense.
	RequireAuth(d, Search(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusServiceUnavailable {
		t.Errorf("status = %d, want 503", rec.Code)
	}
}

// tiny helper to avoid pulling in strings for one call site.
func contains(hay, needle string) bool {
	for i := 0; i+len(needle) <= len(hay); i++ {
		if hay[i:i+len(needle)] == needle {
			return true
		}
	}
	return false
}

// Compile-time check that ErrUpstream is still errors.As-compatible as used
// above.
var _ error = (*tmdb.ErrUpstream)(nil)

// Sanity: make sure errors.As usage in writeTMDBError would actually match
// the pointer-to-ErrUpstream the client returns.
func TestWriteTMDBError_MatchesErrUpstream(t *testing.T) {
	err := &tmdb.ErrUpstream{Status: 500}
	var u *tmdb.ErrUpstream
	if !errors.As(err, &u) {
		t.Fatal("errors.As failed to unwrap ErrUpstream")
	}
}
