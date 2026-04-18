package library

import (
	"database/sql"
	"errors"
	"testing"
	"time"
)

func mustInsertTitle(t *testing.T, db *sql.DB, tmdbID int) Title {
	t.Helper()
	title, err := InsertTitle(db, Title{
		TMDBID: tmdbID, Kind: KindMovie,
		DisplayTitle: "Title", ReleaseYear: 2020, Synopsis: "syn",
	})
	if err != nil {
		t.Fatalf("seed title: %v", err)
	}
	return title
}

func TestInsertEntry_DefaultsToWantStatus(t *testing.T) {
	db := openTestDB(t)
	title := mustInsertTitle(t, db, 1)

	e, err := InsertEntry(db, title.ID, "")
	if err != nil {
		t.Fatalf("insert: %v", err)
	}
	if e.Status != StatusWant {
		t.Errorf("status = %q, want want", e.Status)
	}
	if e.ID == "" || e.AddedAt.IsZero() {
		t.Errorf("id/added_at unpopulated: %+v", e)
	}
	if e.Title.ID != title.ID {
		t.Errorf("entry.title.id = %q, want %q", e.Title.ID, title.ID)
	}
}

func TestInsertEntry_DuplicateTitleReturnsErrEntryAlreadyExists(t *testing.T) {
	db := openTestDB(t)
	title := mustInsertTitle(t, db, 1)
	if _, err := InsertEntry(db, title.ID, StatusWant); err != nil {
		t.Fatalf("first: %v", err)
	}
	_, err := InsertEntry(db, title.ID, StatusWant)
	if !errors.Is(err, ErrEntryAlreadyExists) {
		t.Fatalf("err = %v, want ErrEntryAlreadyExists", err)
	}
}

func TestInsertEntry_RejectsInvalidStatus(t *testing.T) {
	db := openTestDB(t)
	title := mustInsertTitle(t, db, 1)
	if _, err := InsertEntry(db, title.ID, Status("bogus")); err == nil {
		t.Fatal("invalid status must fail")
	}
}

func TestInsertEntry_RequiresTitleID(t *testing.T) {
	db := openTestDB(t)
	if _, err := InsertEntry(db, "", StatusWant); err == nil {
		t.Fatal("blank title_id must fail")
	}
}

func TestListEntries_FiltersByStatus(t *testing.T) {
	db := openTestDB(t)
	t1 := mustInsertTitle(t, db, 1)
	t2 := mustInsertTitle(t, db, 2)
	t3 := mustInsertTitle(t, db, 3)

	if _, err := InsertEntry(db, t1.ID, StatusWant); err != nil {
		t.Fatal(err)
	}
	if _, err := InsertEntry(db, t2.ID, StatusWatching); err != nil {
		t.Fatal(err)
	}
	if _, err := InsertEntry(db, t3.ID, StatusWatched); err != nil {
		t.Fatal(err)
	}

	all, err := ListEntries(db, nil)
	if err != nil {
		t.Fatalf("list all: %v", err)
	}
	if len(all) != 3 {
		t.Errorf("want 3 entries, got %d", len(all))
	}

	plate, err := ListEntries(db, []Status{StatusWant, StatusWatching})
	if err != nil {
		t.Fatalf("list plate: %v", err)
	}
	if len(plate) != 2 {
		t.Errorf("want 2 plate entries, got %d", len(plate))
	}
}

func TestListEntries_OrderIsMostRecentFirst(t *testing.T) {
	db := openTestDB(t)
	t1 := mustInsertTitle(t, db, 1)
	t2 := mustInsertTitle(t, db, 2)
	if _, err := InsertEntry(db, t1.ID, StatusWant); err != nil {
		t.Fatal(err)
	}
	// Sleep to ensure distinct timestamps at second resolution.
	time.Sleep(2 * time.Millisecond)
	if _, err := InsertEntry(db, t2.ID, StatusWant); err != nil {
		t.Fatal(err)
	}

	got, err := ListEntries(db, nil)
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if got[0].Title.ID != t2.ID {
		t.Errorf("most-recent first violated; got %v then %v", got[0].Title.ID, got[1].Title.ID)
	}
}

func TestListEntries_RejectsInvalidFilter(t *testing.T) {
	db := openTestDB(t)
	if _, err := ListEntries(db, []Status{Status("bogus")}); err == nil {
		t.Fatal("invalid status must fail")
	}
}

func TestUpdateEntryStatus_BumpsTimestamp(t *testing.T) {
	db := openTestDB(t)
	title := mustInsertTitle(t, db, 1)
	e1, err := InsertEntry(db, title.ID, StatusWant)
	if err != nil {
		t.Fatalf("insert: %v", err)
	}
	time.Sleep(2 * time.Millisecond)
	e2, err := UpdateEntryStatus(db, e1.ID, StatusWatching)
	if err != nil {
		t.Fatalf("update: %v", err)
	}
	if e2.Status != StatusWatching {
		t.Errorf("status = %q", e2.Status)
	}
	if !e2.StatusUpdatedAt.After(e1.StatusUpdatedAt) {
		t.Errorf("status_updated_at not bumped: %v -> %v", e1.StatusUpdatedAt, e2.StatusUpdatedAt)
	}
}

func TestUpdateEntryStatus_UnknownIDReturnsErrEntryNotFound(t *testing.T) {
	db := openTestDB(t)
	if _, err := UpdateEntryStatus(db, "no-such-id", StatusWatched); !errors.Is(err, ErrEntryNotFound) {
		t.Fatalf("err = %v, want ErrEntryNotFound", err)
	}
}

func TestDeleteEntry_RemovesRow(t *testing.T) {
	db := openTestDB(t)
	title := mustInsertTitle(t, db, 1)
	e, err := InsertEntry(db, title.ID, StatusWant)
	if err != nil {
		t.Fatal(err)
	}
	if err := DeleteEntry(db, e.ID); err != nil {
		t.Fatalf("delete: %v", err)
	}
	if _, err := FindEntryByID(db, e.ID); !errors.Is(err, ErrEntryNotFound) {
		t.Fatalf("post-delete find err = %v, want ErrEntryNotFound", err)
	}
}

func TestDeleteEntry_UnknownIDReturnsErrEntryNotFound(t *testing.T) {
	db := openTestDB(t)
	if err := DeleteEntry(db, "no-such-id"); !errors.Is(err, ErrEntryNotFound) {
		t.Fatalf("err = %v, want ErrEntryNotFound", err)
	}
}

func TestStatus_IsValid(t *testing.T) {
	cases := map[Status]bool{
		StatusWant:      true,
		StatusWatching:  true,
		StatusWatched:   true,
		StatusAbandoned: true,
		Status(""):      false,
		Status("bogus"): false,
	}
	for s, want := range cases {
		if got := s.IsValid(); got != want {
			t.Errorf("IsValid(%q) = %v, want %v", s, got, want)
		}
	}
}
