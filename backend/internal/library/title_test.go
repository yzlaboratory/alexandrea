package library

import (
	"database/sql"
	"errors"
	"path/filepath"
	"testing"
	"time"

	"github.com/yzlaboratory/entertainment-library/backend/internal/storage"
)

func openTestDB(t *testing.T) *sql.DB {
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
	return db
}

func TestInsertTitle_RoundTripsAllFields(t *testing.T) {
	db := openTestDB(t)
	in := Title{
		TMDBID:       27205,
		Kind:         KindMovie,
		DisplayTitle: "Inception",
		ReleaseYear:  2010,
		Synopsis:     "A heist film about dreams.",
		PosterPath:   "/oRjpOUqco1pzxNeS9uggdlSbtHp.jpg",
	}
	out, err := InsertTitle(db, in)
	if err != nil {
		t.Fatalf("insert: %v", err)
	}
	if out.ID == "" {
		t.Errorf("id should be auto-generated")
	}
	if out.AddedAt.IsZero() {
		t.Errorf("added_at should be auto-set")
	}

	got, err := FindTitleByTMDBID(db, 27205)
	if err != nil {
		t.Fatalf("find: %v", err)
	}
	if got.ID != out.ID || got.DisplayTitle != "Inception" || got.PosterPath != in.PosterPath {
		t.Errorf("round-trip mismatch: %+v", got)
	}
}

func TestInsertTitle_NilPosterPathStoredAsNull(t *testing.T) {
	db := openTestDB(t)
	in := Title{
		TMDBID:       1,
		Kind:         KindSeries,
		DisplayTitle: "Show",
		ReleaseYear:  2020,
		Synopsis:     "...",
		PosterPath:   "", // intentionally empty
	}
	if _, err := InsertTitle(db, in); err != nil {
		t.Fatalf("insert: %v", err)
	}
	var posterPath sql.NullString
	if err := db.QueryRow(`SELECT poster_path FROM title WHERE tmdb_id = 1`).Scan(&posterPath); err != nil {
		t.Fatalf("select: %v", err)
	}
	if posterPath.Valid {
		t.Errorf("expected NULL poster_path, got %q", posterPath.String)
	}
}

func TestInsertTitle_RejectsInvalidKind(t *testing.T) {
	db := openTestDB(t)
	_, err := InsertTitle(db, Title{TMDBID: 1, Kind: Kind("person"), DisplayTitle: "x", ReleaseYear: 2020, Synopsis: "."})
	if err == nil {
		t.Fatal("expected validation error for kind=person")
	}
}

func TestInsertTitle_DuplicateTMDBIDFails(t *testing.T) {
	db := openTestDB(t)
	in := Title{TMDBID: 42, Kind: KindMovie, DisplayTitle: "x", ReleaseYear: 2020, Synopsis: "y"}
	if _, err := InsertTitle(db, in); err != nil {
		t.Fatalf("first insert: %v", err)
	}
	if _, err := InsertTitle(db, in); err == nil {
		t.Fatal("second insert with same tmdb_id should fail (UNIQUE)")
	}
}

func TestFindTitleByTMDBID_ReturnsErrTitleNotFoundOnMiss(t *testing.T) {
	db := openTestDB(t)
	_, err := FindTitleByTMDBID(db, 999999)
	if !errors.Is(err, ErrTitleNotFound) {
		t.Fatalf("err = %v, want ErrTitleNotFound", err)
	}
}

func TestFindTitleByID_ReturnsErrTitleNotFoundOnMiss(t *testing.T) {
	db := openTestDB(t)
	_, err := FindTitleByID(db, "no-such-id")
	if !errors.Is(err, ErrTitleNotFound) {
		t.Fatalf("err = %v, want ErrTitleNotFound", err)
	}
}

func TestInsertTitle_PreservesExplicitIDAndTimestamp(t *testing.T) {
	db := openTestDB(t)
	stamp := time.Date(2026, 4, 18, 12, 0, 0, 0, time.UTC)
	in := Title{
		ID: "explicit-id", TMDBID: 7, Kind: KindMovie,
		DisplayTitle: "x", ReleaseYear: 2020, Synopsis: "y",
		AddedAt: stamp,
	}
	out, err := InsertTitle(db, in)
	if err != nil {
		t.Fatalf("insert: %v", err)
	}
	if out.ID != "explicit-id" {
		t.Errorf("id overridden: %q", out.ID)
	}
	got, _ := FindTitleByID(db, "explicit-id")
	if !got.AddedAt.Equal(stamp) {
		t.Errorf("added_at = %v, want %v", got.AddedAt, stamp)
	}
}
