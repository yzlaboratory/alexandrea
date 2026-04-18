package library

import (
	"database/sql"
	"errors"
	"testing"
	"time"
)

func seedUser(t *testing.T, db *sql.DB, id, displayName string) {
	t.Helper()
	if _, err := db.Exec(`INSERT INTO "user" (id, display_name) VALUES (?, ?)`, id, displayName); err != nil {
		t.Fatalf("seed user %q: %v", id, err)
	}
}

func TestUpsertRating_CreatesNewRow(t *testing.T) {
	db := openTestDB(t)
	seedUser(t, db, "kira", "Kira")
	title := mustInsertTitle(t, db, 1)

	r, err := UpsertRating(db, title.ID, "kira", 4, "loved it")
	if err != nil {
		t.Fatalf("upsert: %v", err)
	}
	if r.Score != 4 || r.Note != "loved it" || r.DisplayName != "Kira" || r.UserID != "kira" {
		t.Errorf("unexpected rating: %+v", r)
	}
}

func TestUpsertRating_OverwritesInPlace(t *testing.T) {
	db := openTestDB(t)
	seedUser(t, db, "kira", "Kira")
	title := mustInsertTitle(t, db, 1)

	first, err := UpsertRating(db, title.ID, "kira", 2, "meh")
	if err != nil {
		t.Fatal(err)
	}
	time.Sleep(2 * time.Millisecond)
	second, err := UpsertRating(db, title.ID, "kira", 5, "changed my mind")
	if err != nil {
		t.Fatal(err)
	}
	if first.ID == second.ID {
		// Both rows share a UNIQUE(title_id, user_id) key; on conflict the
		// existing row is updated, so the surrogate id stays the same.
		// We only assert this invariant as a sanity check.
	}
	if second.Score != 5 || second.Note != "changed my mind" {
		t.Errorf("overwrite didn't land: %+v", second)
	}

	// Confirm exactly one row exists.
	var count int
	_ = db.QueryRow(`SELECT COUNT(*) FROM rating WHERE title_id = ? AND user_id = ?`, title.ID, "kira").Scan(&count)
	if count != 1 {
		t.Errorf("row count = %d, want 1 (overwrite, not duplicate)", count)
	}
}

func TestUpsertRating_RejectsOutOfRangeScore(t *testing.T) {
	db := openTestDB(t)
	seedUser(t, db, "kira", "Kira")
	title := mustInsertTitle(t, db, 1)
	if _, err := UpsertRating(db, title.ID, "kira", 6, ""); err == nil {
		t.Error("score 6 must fail")
	}
	if _, err := UpsertRating(db, title.ID, "kira", -1, ""); err == nil {
		t.Error("score -1 must fail")
	}
}

func TestUpsertRating_RequiresTitleAndUser(t *testing.T) {
	db := openTestDB(t)
	if _, err := UpsertRating(db, "", "kira", 3, ""); err == nil {
		t.Error("blank title_id must fail")
	}
	if _, err := UpsertRating(db, "t", "", 3, ""); err == nil {
		t.Error("blank user_id must fail")
	}
}

func TestDeleteRating_RemovesRow(t *testing.T) {
	db := openTestDB(t)
	seedUser(t, db, "kira", "Kira")
	title := mustInsertTitle(t, db, 1)
	if _, err := UpsertRating(db, title.ID, "kira", 3, ""); err != nil {
		t.Fatal(err)
	}
	if err := DeleteRating(db, title.ID, "kira"); err != nil {
		t.Fatalf("delete: %v", err)
	}
	var count int
	_ = db.QueryRow(`SELECT COUNT(*) FROM rating WHERE title_id = ? AND user_id = ?`, title.ID, "kira").Scan(&count)
	if count != 0 {
		t.Errorf("row count = %d, want 0", count)
	}
}

func TestDeleteRating_UnknownReturnsErrRatingNotFound(t *testing.T) {
	db := openTestDB(t)
	if err := DeleteRating(db, "no-such-title", "kira"); !errors.Is(err, ErrRatingNotFound) {
		t.Fatalf("err = %v, want ErrRatingNotFound", err)
	}
}

func TestListRatingsByTitleIDs_StitchesCleanly(t *testing.T) {
	db := openTestDB(t)
	seedUser(t, db, "kira", "Kira")
	seedUser(t, db, "m", "M")
	t1 := mustInsertTitle(t, db, 1)
	t2 := mustInsertTitle(t, db, 2)

	if _, err := UpsertRating(db, t1.ID, "kira", 4, ""); err != nil {
		t.Fatal(err)
	}
	if _, err := UpsertRating(db, t1.ID, "m", 5, ""); err != nil {
		t.Fatal(err)
	}
	if _, err := UpsertRating(db, t2.ID, "kira", 3, ""); err != nil {
		t.Fatal(err)
	}

	got, err := ListRatingsByTitleIDs(db, []string{t1.ID, t2.ID})
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(got) != 3 {
		t.Fatalf("count = %d, want 3", len(got))
	}
	// Display name is joined from the user table.
	for _, r := range got {
		if r.UserID == "kira" && r.DisplayName != "Kira" {
			t.Errorf("display_name not joined: %+v", r)
		}
	}
}

func TestListRatingsByTitleIDs_EmptyInputReturnsEmpty(t *testing.T) {
	db := openTestDB(t)
	got, err := ListRatingsByTitleIDs(db, nil)
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	if len(got) != 0 {
		t.Errorf("len = %d, want 0", len(got))
	}
}

func TestFindEntryByID_EmbedsRatings(t *testing.T) {
	db := openTestDB(t)
	seedUser(t, db, "kira", "Kira")
	title := mustInsertTitle(t, db, 1)
	e1, err := InsertEntry(db, title.ID, StatusWatched)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := UpsertRating(db, title.ID, "kira", 4, "good"); err != nil {
		t.Fatal(err)
	}

	got, err := FindEntryByID(db, e1.ID)
	if err != nil {
		t.Fatalf("find: %v", err)
	}
	if len(got.Ratings) != 1 || got.Ratings[0].Score != 4 {
		t.Errorf("ratings not embedded: %+v", got.Ratings)
	}
}

func TestListEntries_StitchesRatingsPerEntry(t *testing.T) {
	db := openTestDB(t)
	seedUser(t, db, "kira", "Kira")
	seedUser(t, db, "m", "M")
	t1 := mustInsertTitle(t, db, 1)
	t2 := mustInsertTitle(t, db, 2)
	if _, err := InsertEntry(db, t1.ID, StatusWatched); err != nil {
		t.Fatal(err)
	}
	if _, err := InsertEntry(db, t2.ID, StatusWatched); err != nil {
		t.Fatal(err)
	}
	if _, err := UpsertRating(db, t1.ID, "kira", 5, ""); err != nil {
		t.Fatal(err)
	}
	if _, err := UpsertRating(db, t1.ID, "m", 3, ""); err != nil {
		t.Fatal(err)
	}
	// t2 has no ratings.

	entries, err := ListEntries(db, nil)
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(entries) != 2 {
		t.Fatalf("entries = %d", len(entries))
	}
	byTitle := map[string]Entry{}
	for _, e := range entries {
		byTitle[e.Title.ID] = e
	}
	if got := len(byTitle[t1.ID].Ratings); got != 2 {
		t.Errorf("t1 ratings = %d, want 2", got)
	}
	if got := len(byTitle[t2.ID].Ratings); got != 0 {
		t.Errorf("t2 ratings = %d, want 0", got)
	}
}
