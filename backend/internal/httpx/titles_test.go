package httpx

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/yzlaboratory/entertainment-library/backend/internal/library"
	"github.com/yzlaboratory/entertainment-library/backend/internal/tmdb"
)

func TestAddTitle_RequiresAuth(t *testing.T) {
	d, _ := testDeps(t)
	d.TMDB = &fakeTMDB{}
	req := httptest.NewRequest(http.MethodPost, "/api/titles",
		strings.NewReader(`{"tmdb_id":1,"kind":"movie"}`))
	rec := httptest.NewRecorder()
	RequireAuth(d, AddTitle(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", rec.Code)
	}
}

func TestAddTitle_FirstAddFetchesFromTMDBAndReturns201(t *testing.T) {
	fake := &fakeTMDB{
		get: func(_ context.Context, kind tmdb.Kind, id int) (tmdb.Result, error) {
			return tmdb.Result{
				TMDBID: id, Kind: kind, Title: "Inception", Year: 2010,
				PosterPath: "/p.jpg", Overview: "dreams.",
			}, nil
		},
	}
	d, sid := authedDeps(t, fake)
	req := httptest.NewRequest(http.MethodPost, "/api/titles",
		strings.NewReader(`{"tmdb_id":27205,"kind":"movie"}`))
	req.AddCookie(&http.Cookie{Name: sessionCookieName, Value: sid})
	rec := httptest.NewRecorder()
	RequireAuth(d, AddTitle(d)).ServeHTTP(rec, req)

	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, body=%s", rec.Code, rec.Body.String())
	}
	var got library.Title
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if got.TMDBID != 27205 || got.DisplayTitle != "Inception" || got.ID == "" {
		t.Errorf("unexpected body: %+v", got)
	}

	// And it should now be findable.
	if _, err := library.FindTitleByTMDBID(d.DB, 27205); err != nil {
		t.Errorf("title not persisted: %v", err)
	}
}

func TestAddTitle_IdempotentReturns200WithoutCallingTMDB(t *testing.T) {
	called := 0
	fake := &fakeTMDB{
		get: func(_ context.Context, kind tmdb.Kind, id int) (tmdb.Result, error) {
			called++
			return tmdb.Result{TMDBID: id, Kind: kind, Title: "Pre-existing", Year: 2001}, nil
		},
	}
	d, sid := authedDeps(t, fake)

	// First call: create.
	req1 := httptest.NewRequest(http.MethodPost, "/api/titles",
		strings.NewReader(`{"tmdb_id":7,"kind":"movie"}`))
	req1.AddCookie(&http.Cookie{Name: sessionCookieName, Value: sid})
	rec1 := httptest.NewRecorder()
	RequireAuth(d, AddTitle(d)).ServeHTTP(rec1, req1)
	if rec1.Code != http.StatusCreated {
		t.Fatalf("first status = %d", rec1.Code)
	}

	// Second call: should hit the existing-row branch and return 200.
	req2 := httptest.NewRequest(http.MethodPost, "/api/titles",
		strings.NewReader(`{"tmdb_id":7,"kind":"movie"}`))
	req2.AddCookie(&http.Cookie{Name: sessionCookieName, Value: sid})
	rec2 := httptest.NewRecorder()
	RequireAuth(d, AddTitle(d)).ServeHTTP(rec2, req2)
	if rec2.Code != http.StatusOK {
		t.Fatalf("second status = %d (want 200), body=%s", rec2.Code, rec2.Body.String())
	}
	if called != 1 {
		t.Errorf("TMDB.Get called %d times, want 1 (second call must hit cache)", called)
	}

	// Both bodies should reference the same id.
	var first, second library.Title
	_ = json.Unmarshal(rec1.Body.Bytes(), &first)
	_ = json.Unmarshal(rec2.Body.Bytes(), &second)
	if first.ID != second.ID {
		t.Errorf("ids diverged: %q vs %q", first.ID, second.ID)
	}
}

func TestAddTitle_BadJSONReturns400(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	req := httptest.NewRequest(http.MethodPost, "/api/titles", strings.NewReader(`{`))
	req.AddCookie(&http.Cookie{Name: sessionCookieName, Value: sid})
	rec := httptest.NewRecorder()
	RequireAuth(d, AddTitle(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("status = %d, want 400", rec.Code)
	}
}

func TestAddTitle_BadKindReturns400(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	req := httptest.NewRequest(http.MethodPost, "/api/titles",
		strings.NewReader(`{"tmdb_id":1,"kind":"person"}`))
	req.AddCookie(&http.Cookie{Name: sessionCookieName, Value: sid})
	rec := httptest.NewRecorder()
	RequireAuth(d, AddTitle(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("status = %d, want 400", rec.Code)
	}
}

func TestAddTitle_MissingTMDBIDReturns400(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	req := httptest.NewRequest(http.MethodPost, "/api/titles",
		strings.NewReader(`{"kind":"movie"}`))
	req.AddCookie(&http.Cookie{Name: sessionCookieName, Value: sid})
	rec := httptest.NewRecorder()
	RequireAuth(d, AddTitle(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("status = %d, want 400", rec.Code)
	}
}

func TestAddTitle_TMDBNotFoundMapsTo404(t *testing.T) {
	fake := &fakeTMDB{
		get: func(context.Context, tmdb.Kind, int) (tmdb.Result, error) {
			return tmdb.Result{}, tmdb.ErrNotFound
		},
	}
	d, sid := authedDeps(t, fake)
	req := httptest.NewRequest(http.MethodPost, "/api/titles",
		strings.NewReader(`{"tmdb_id":9999999,"kind":"movie"}`))
	req.AddCookie(&http.Cookie{Name: sessionCookieName, Value: sid})
	rec := httptest.NewRecorder()
	RequireAuth(d, AddTitle(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusNotFound {
		t.Errorf("status = %d, want 404", rec.Code)
	}
}

func TestGetTitle_HappyPath(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	t1, err := library.InsertTitle(d.DB, library.Title{
		TMDBID: 1, Kind: library.KindMovie, DisplayTitle: "x", ReleaseYear: 2020, Synopsis: "y",
	})
	if err != nil {
		t.Fatalf("seed: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/api/titles/"+t1.ID, nil)
	req.AddCookie(&http.Cookie{Name: sessionCookieName, Value: sid})
	rec := httptest.NewRecorder()
	NewRouter(d).ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body=%s", rec.Code, rec.Body.String())
	}
	var got library.Title
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if got.ID != t1.ID || got.DisplayTitle != "x" {
		t.Errorf("got = %+v", got)
	}
}

func TestGetTitle_UnknownIDReturns404(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	req := httptest.NewRequest(http.MethodGet, "/api/titles/no-such-id", nil)
	req.AddCookie(&http.Cookie{Name: sessionCookieName, Value: sid})
	rec := httptest.NewRecorder()
	NewRouter(d).ServeHTTP(rec, req)
	if rec.Code != http.StatusNotFound {
		t.Errorf("status = %d, want 404", rec.Code)
	}
}

func TestGetTitle_AvailableEvenWhenTMDBDisabled(t *testing.T) {
	// d.TMDB nil — GetTitle should still work, since it never calls TMDB.
	d, _ := testDeps(t)
	sess, err := d.Sessions.Mint("kira")
	if err != nil {
		t.Fatalf("mint: %v", err)
	}
	t1, err := library.InsertTitle(d.DB, library.Title{
		TMDBID: 1, Kind: library.KindMovie, DisplayTitle: "x", ReleaseYear: 2020, Synopsis: "y",
	})
	if err != nil {
		t.Fatalf("seed: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/api/titles/"+t1.ID, nil)
	req.AddCookie(&http.Cookie{Name: sessionCookieName, Value: sess.ID})
	rec := httptest.NewRecorder()
	NewRouter(d).ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Errorf("status = %d, want 200 (GetTitle is TMDB-independent)", rec.Code)
	}
}
