package httpx

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/yzlaboratory/entertainment-library/backend/internal/library"
)

func seedTitle(t *testing.T, d Deps, tmdbID int) library.Title {
	t.Helper()
	title, err := library.InsertTitle(d.DB, library.Title{
		TMDBID: tmdbID, Kind: library.KindMovie,
		DisplayTitle: fmt.Sprintf("Title %d", tmdbID), ReleaseYear: 2020, Synopsis: "syn",
	})
	if err != nil {
		t.Fatalf("seed title: %v", err)
	}
	return title
}

func authedReq(t *testing.T, sid, method, target, body string) *http.Request {
	t.Helper()
	var r *http.Request
	if body == "" {
		r = httptest.NewRequest(method, target, nil)
	} else {
		r = httptest.NewRequest(method, target, strings.NewReader(body))
	}
	r.AddCookie(&http.Cookie{Name: sessionCookieName, Value: sid})
	return r
}

func TestListLibrary_RequiresAuth(t *testing.T) {
	d, _ := testDeps(t)
	req := httptest.NewRequest(http.MethodGet, "/api/library", nil)
	rec := httptest.NewRecorder()
	RequireAuth(d, ListLibrary(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", rec.Code)
	}
}

func TestListLibrary_DefaultFilterIsWantAndWatching(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	t1 := seedTitle(t, d, 1)
	t2 := seedTitle(t, d, 2)
	t3 := seedTitle(t, d, 3)
	if _, err := library.InsertEntry(d.DB, t1.ID, library.StatusWant); err != nil {
		t.Fatal(err)
	}
	if _, err := library.InsertEntry(d.DB, t2.ID, library.StatusWatching); err != nil {
		t.Fatal(err)
	}
	if _, err := library.InsertEntry(d.DB, t3.ID, library.StatusWatched); err != nil {
		t.Fatal(err)
	}

	req := authedReq(t, sid, http.MethodGet, "/api/library", "")
	rec := httptest.NewRecorder()
	NewRouter(d).ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d", rec.Code)
	}
	var body struct {
		Entries []library.Entry `json:"entries"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(body.Entries) != 2 {
		t.Errorf("default filter returned %d entries, want 2 (want+watching)", len(body.Entries))
	}
}

func TestListLibrary_StatusQuerySelectsWatched(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	t1 := seedTitle(t, d, 1)
	if _, err := library.InsertEntry(d.DB, t1.ID, library.StatusWatched); err != nil {
		t.Fatal(err)
	}

	req := authedReq(t, sid, http.MethodGet, "/api/library?status=watched", "")
	rec := httptest.NewRecorder()
	NewRouter(d).ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d", rec.Code)
	}
	var body struct {
		Entries []library.Entry `json:"entries"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &body)
	if len(body.Entries) != 1 || body.Entries[0].Status != library.StatusWatched {
		t.Errorf("got %+v", body.Entries)
	}
}

func TestListLibrary_InvalidStatusFilterReturns400(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	req := authedReq(t, sid, http.MethodGet, "/api/library?status=bogus", "")
	rec := httptest.NewRecorder()
	NewRouter(d).ServeHTTP(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("status = %d, want 400", rec.Code)
	}
}

func TestListLibrary_EmptyResultIsArrayNotNull(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	req := authedReq(t, sid, http.MethodGet, "/api/library", "")
	rec := httptest.NewRecorder()
	NewRouter(d).ServeHTTP(rec, req)
	if !contains(rec.Body.String(), `"entries":[]`) {
		t.Errorf("body = %s; expected entries:[]", rec.Body.String())
	}
}

// Mutating handler tests skip the router (and thus CSRF) and exercise the
// handler through RequireAuth directly, mirroring the slice-5 + slice-10
// convention. Read-only tests can go through the full NewRouter since GETs
// are CSRF-exempt.

func TestAddLibraryEntry_HappyPath(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	tt := seedTitle(t, d, 1)

	body := fmt.Sprintf(`{"title_id":%q,"status":"watching"}`, tt.ID)
	req := authedReq(t, sid, http.MethodPost, "/api/library", body)
	rec := httptest.NewRecorder()
	RequireAuth(d, AddLibraryEntry(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, body=%s", rec.Code, rec.Body.String())
	}
	var got library.Entry
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if got.Status != library.StatusWatching || got.Title.ID != tt.ID {
		t.Errorf("got = %+v", got)
	}
}

func TestAddLibraryEntry_StatusDefaultsToWant(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	tt := seedTitle(t, d, 1)
	body := fmt.Sprintf(`{"title_id":%q}`, tt.ID)
	req := authedReq(t, sid, http.MethodPost, "/api/library", body)
	rec := httptest.NewRecorder()
	RequireAuth(d, AddLibraryEntry(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d", rec.Code)
	}
	var got library.Entry
	_ = json.Unmarshal(rec.Body.Bytes(), &got)
	if got.Status != library.StatusWant {
		t.Errorf("status = %q, want want", got.Status)
	}
}

func TestAddLibraryEntry_UnknownTitleReturns404(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	body := `{"title_id":"no-such-id","status":"want"}`
	req := authedReq(t, sid, http.MethodPost, "/api/library", body)
	rec := httptest.NewRecorder()
	RequireAuth(d, AddLibraryEntry(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusNotFound {
		t.Errorf("status = %d, want 404", rec.Code)
	}
}

func TestAddLibraryEntry_DuplicateReturns409(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	tt := seedTitle(t, d, 1)
	body := fmt.Sprintf(`{"title_id":%q}`, tt.ID)

	req := authedReq(t, sid, http.MethodPost, "/api/library", body)
	rec := httptest.NewRecorder()
	RequireAuth(d, AddLibraryEntry(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusCreated {
		t.Fatalf("first status = %d", rec.Code)
	}

	req2 := authedReq(t, sid, http.MethodPost, "/api/library", body)
	rec2 := httptest.NewRecorder()
	RequireAuth(d, AddLibraryEntry(d)).ServeHTTP(rec2, req2)
	if rec2.Code != http.StatusConflict {
		t.Errorf("second status = %d, want 409", rec2.Code)
	}
}

func TestAddLibraryEntry_BadStatusReturns400(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	tt := seedTitle(t, d, 1)
	body := fmt.Sprintf(`{"title_id":%q,"status":"bogus"}`, tt.ID)
	req := authedReq(t, sid, http.MethodPost, "/api/library", body)
	rec := httptest.NewRecorder()
	RequireAuth(d, AddLibraryEntry(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("status = %d, want 400", rec.Code)
	}
}

func TestUpdateLibraryEntryStatus_HappyPath(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	tt := seedTitle(t, d, 1)
	e, err := library.InsertEntry(d.DB, tt.ID, library.StatusWant)
	if err != nil {
		t.Fatalf("seed entry: %v", err)
	}

	body := `{"status":"watched"}`
	req := authedReq(t, sid, http.MethodPatch, "/api/library/"+e.ID, body)
	req.SetPathValue("id", e.ID)
	rec := httptest.NewRecorder()
	RequireAuth(d, UpdateLibraryEntryStatus(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body=%s", rec.Code, rec.Body.String())
	}
	var got library.Entry
	_ = json.Unmarshal(rec.Body.Bytes(), &got)
	if got.Status != library.StatusWatched {
		t.Errorf("status = %q, want watched", got.Status)
	}
}

func TestUpdateLibraryEntryStatus_UnknownIDReturns404(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	body := `{"status":"watched"}`
	req := authedReq(t, sid, http.MethodPatch, "/api/library/no-such-id", body)
	req.SetPathValue("id", "no-such-id")
	rec := httptest.NewRecorder()
	RequireAuth(d, UpdateLibraryEntryStatus(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusNotFound {
		t.Errorf("status = %d, want 404", rec.Code)
	}
}

func TestUpdateLibraryEntryStatus_BadStatusReturns400(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	tt := seedTitle(t, d, 1)
	e, err := library.InsertEntry(d.DB, tt.ID, library.StatusWant)
	if err != nil {
		t.Fatal(err)
	}
	body := `{"status":"bogus"}`
	req := authedReq(t, sid, http.MethodPatch, "/api/library/"+e.ID, body)
	req.SetPathValue("id", e.ID)
	rec := httptest.NewRecorder()
	RequireAuth(d, UpdateLibraryEntryStatus(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("status = %d, want 400", rec.Code)
	}
}

func TestDeleteLibraryEntry_HappyPathReturns204(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	tt := seedTitle(t, d, 1)
	e, err := library.InsertEntry(d.DB, tt.ID, library.StatusWant)
	if err != nil {
		t.Fatal(err)
	}

	req := authedReq(t, sid, http.MethodDelete, "/api/library/"+e.ID, "")
	req.SetPathValue("id", e.ID)
	rec := httptest.NewRecorder()
	RequireAuth(d, DeleteLibraryEntry(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusNoContent {
		t.Errorf("status = %d, want 204", rec.Code)
	}
}

func TestDeleteLibraryEntry_UnknownIDReturns404(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	req := authedReq(t, sid, http.MethodDelete, "/api/library/no-such-id", "")
	req.SetPathValue("id", "no-such-id")
	rec := httptest.NewRecorder()
	RequireAuth(d, DeleteLibraryEntry(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusNotFound {
		t.Errorf("status = %d, want 404", rec.Code)
	}
}
