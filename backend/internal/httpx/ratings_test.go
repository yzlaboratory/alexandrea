package httpx

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/yzlaboratory/entertainment-library/backend/internal/library"
)

// seedSecondUser inserts a second user the tests use to exercise
// two-rater scenarios and the "can't delete another user's rating" rule.
func seedSecondUser(t *testing.T, db *sql.DB) {
	t.Helper()
	if _, err := db.Exec(`INSERT INTO "user" (id, display_name) VALUES (?, ?)`, "m", "M"); err != nil {
		t.Fatalf("seed m: %v", err)
	}
}

func TestUpsertRating_RequiresAuth(t *testing.T) {
	d, _ := testDeps(t)
	req := httptest.NewRequest(http.MethodPost, "/api/library/x/rating", strings.NewReader(`{"score":3}`))
	rec := httptest.NewRecorder()
	RequireAuth(d, UpsertRating(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", rec.Code)
	}
}

func TestUpsertRating_HappyPathReturnsEntryWithRating(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	tt := seedTitle(t, d, 1)
	e, err := library.InsertEntry(d.DB, tt.ID, library.StatusWant)
	if err != nil {
		t.Fatal(err)
	}

	body := `{"score":4,"note":"good"}`
	req := authedReq(t, sid, http.MethodPost, "/api/library/"+e.ID+"/rating", body)
	req.SetPathValue("id", e.ID)
	rec := httptest.NewRecorder()
	RequireAuth(d, UpsertRating(d)).ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body=%s", rec.Code, rec.Body.String())
	}
	var got library.Entry
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(got.Ratings) != 1 || got.Ratings[0].Score != 4 {
		t.Errorf("rating not embedded: %+v", got.Ratings)
	}
}

func TestUpsertRating_TransitionsToWatchedIfNotAlready(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	tt := seedTitle(t, d, 1)
	e, err := library.InsertEntry(d.DB, tt.ID, library.StatusWant)
	if err != nil {
		t.Fatal(err)
	}

	body := `{"score":3}`
	req := authedReq(t, sid, http.MethodPost, "/api/library/"+e.ID+"/rating", body)
	req.SetPathValue("id", e.ID)
	rec := httptest.NewRecorder()
	RequireAuth(d, UpsertRating(d)).ServeHTTP(rec, req)

	var got library.Entry
	_ = json.Unmarshal(rec.Body.Bytes(), &got)
	if got.Status != library.StatusWatched {
		t.Errorf("status = %q, want watched (implicit transition per spec)", got.Status)
	}
}

func TestUpsertRating_WatchedEntryKeepsStatus(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	tt := seedTitle(t, d, 1)
	e, err := library.InsertEntry(d.DB, tt.ID, library.StatusWatched)
	if err != nil {
		t.Fatal(err)
	}
	body := `{"score":3}`
	req := authedReq(t, sid, http.MethodPost, "/api/library/"+e.ID+"/rating", body)
	req.SetPathValue("id", e.ID)
	rec := httptest.NewRecorder()
	RequireAuth(d, UpsertRating(d)).ServeHTTP(rec, req)
	var got library.Entry
	_ = json.Unmarshal(rec.Body.Bytes(), &got)
	if got.Status != library.StatusWatched {
		t.Errorf("status drifted to %q", got.Status)
	}
}

func TestUpsertRating_OverwritesOwnRating(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	tt := seedTitle(t, d, 1)
	e, err := library.InsertEntry(d.DB, tt.ID, library.StatusWatched)
	if err != nil {
		t.Fatal(err)
	}

	// First rating.
	req := authedReq(t, sid, http.MethodPost, "/api/library/"+e.ID+"/rating", `{"score":1,"note":"bad"}`)
	req.SetPathValue("id", e.ID)
	RequireAuth(d, UpsertRating(d)).ServeHTTP(httptest.NewRecorder(), req)

	// Overwrite.
	req2 := authedReq(t, sid, http.MethodPost, "/api/library/"+e.ID+"/rating", `{"score":5,"note":"great"}`)
	req2.SetPathValue("id", e.ID)
	rec2 := httptest.NewRecorder()
	RequireAuth(d, UpsertRating(d)).ServeHTTP(rec2, req2)
	if rec2.Code != http.StatusOK {
		t.Fatalf("status = %d", rec2.Code)
	}
	var got library.Entry
	_ = json.Unmarshal(rec2.Body.Bytes(), &got)
	if len(got.Ratings) != 1 {
		t.Fatalf("rating count = %d, want 1 (overwrite, not duplicate)", len(got.Ratings))
	}
	if got.Ratings[0].Score != 5 || got.Ratings[0].Note != "great" {
		t.Errorf("overwrite didn't land: %+v", got.Ratings[0])
	}
}

func TestUpsertRating_PreservesOtherUsersRating(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	seedSecondUser(t, d.DB)
	tt := seedTitle(t, d, 1)
	e, err := library.InsertEntry(d.DB, tt.ID, library.StatusWatched)
	if err != nil {
		t.Fatal(err)
	}
	// Other user already rated.
	if _, err := library.UpsertRating(d.DB, tt.ID, "m", 2, "ok"); err != nil {
		t.Fatal(err)
	}

	// Caller (kira) adds their rating.
	req := authedReq(t, sid, http.MethodPost, "/api/library/"+e.ID+"/rating", `{"score":5}`)
	req.SetPathValue("id", e.ID)
	rec := httptest.NewRecorder()
	RequireAuth(d, UpsertRating(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d", rec.Code)
	}
	var got library.Entry
	_ = json.Unmarshal(rec.Body.Bytes(), &got)
	if len(got.Ratings) != 2 {
		t.Errorf("ratings count = %d, want 2 (m + kira)", len(got.Ratings))
	}
}

func TestUpsertRating_BadScoreReturns400(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	tt := seedTitle(t, d, 1)
	e, _ := library.InsertEntry(d.DB, tt.ID, library.StatusWant)

	for _, body := range []string{`{"score":-1}`, `{"score":6}`, `{}`, `{"note":"no score"}`} {
		req := authedReq(t, sid, http.MethodPost, "/api/library/"+e.ID+"/rating", body)
		req.SetPathValue("id", e.ID)
		rec := httptest.NewRecorder()
		RequireAuth(d, UpsertRating(d)).ServeHTTP(rec, req)
		if rec.Code != http.StatusBadRequest {
			t.Errorf("body %s status = %d, want 400", body, rec.Code)
		}
	}
}

func TestUpsertRating_ScoreZeroIsValid(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	tt := seedTitle(t, d, 1)
	e, _ := library.InsertEntry(d.DB, tt.ID, library.StatusWatched)

	req := authedReq(t, sid, http.MethodPost, "/api/library/"+e.ID+"/rating", `{"score":0}`)
	req.SetPathValue("id", e.ID)
	rec := httptest.NewRecorder()
	RequireAuth(d, UpsertRating(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Errorf("score 0 must be accepted; status = %d", rec.Code)
	}
}

func TestUpsertRating_UnknownEntryReturns404(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	req := authedReq(t, sid, http.MethodPost, "/api/library/no-such-id/rating", `{"score":3}`)
	req.SetPathValue("id", "no-such-id")
	rec := httptest.NewRecorder()
	RequireAuth(d, UpsertRating(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusNotFound {
		t.Errorf("status = %d, want 404", rec.Code)
	}
}

func TestUpsertRating_OverlongNoteReturns400(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	tt := seedTitle(t, d, 1)
	e, _ := library.InsertEntry(d.DB, tt.ID, library.StatusWatched)

	longNote := strings.Repeat("x", maxNoteLen+1)
	body := fmt.Sprintf(`{"score":3,"note":%q}`, longNote)
	req := authedReq(t, sid, http.MethodPost, "/api/library/"+e.ID+"/rating", body)
	req.SetPathValue("id", e.ID)
	rec := httptest.NewRecorder()
	RequireAuth(d, UpsertRating(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("status = %d, want 400", rec.Code)
	}
}

func TestDeleteRating_RemovesOnlyCallersRow(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	seedSecondUser(t, d.DB)
	tt := seedTitle(t, d, 1)
	e, _ := library.InsertEntry(d.DB, tt.ID, library.StatusWatched)
	if _, err := library.UpsertRating(d.DB, tt.ID, "kira", 5, ""); err != nil {
		t.Fatal(err)
	}
	if _, err := library.UpsertRating(d.DB, tt.ID, "m", 3, ""); err != nil {
		t.Fatal(err)
	}

	req := authedReq(t, sid, http.MethodDelete, "/api/library/"+e.ID+"/rating", "")
	req.SetPathValue("id", e.ID)
	rec := httptest.NewRecorder()
	RequireAuth(d, DeleteRating(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want 204", rec.Code)
	}

	// The other user's rating should still be there.
	after, _ := library.FindEntryByID(d.DB, e.ID)
	if len(after.Ratings) != 1 || after.Ratings[0].UserID != "m" {
		t.Errorf("expected m's rating to remain; got %+v", after.Ratings)
	}
}

func TestDeleteRating_NoRatingReturns404(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	tt := seedTitle(t, d, 1)
	e, _ := library.InsertEntry(d.DB, tt.ID, library.StatusWatched)

	req := authedReq(t, sid, http.MethodDelete, "/api/library/"+e.ID+"/rating", "")
	req.SetPathValue("id", e.ID)
	rec := httptest.NewRecorder()
	RequireAuth(d, DeleteRating(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusNotFound {
		t.Errorf("status = %d, want 404", rec.Code)
	}
}

func TestDeleteRating_UnknownEntryReturns404(t *testing.T) {
	d, sid := authedDeps(t, &fakeTMDB{})
	req := authedReq(t, sid, http.MethodDelete, "/api/library/no-such-id/rating", "")
	req.SetPathValue("id", "no-such-id")
	rec := httptest.NewRecorder()
	RequireAuth(d, DeleteRating(d)).ServeHTTP(rec, req)
	if rec.Code != http.StatusNotFound {
		t.Errorf("status = %d, want 404", rec.Code)
	}
}
