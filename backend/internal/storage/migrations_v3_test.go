package storage

import (
	"database/sql"
	"path/filepath"
	"testing"
)

func openMigratedDB(t *testing.T) *sql.DB {
	t.Helper()
	dsn := filepath.Join(t.TempDir(), "test.sqlite")
	db, err := Open(dsn)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })
	if err := Migrate(db); err != nil {
		t.Fatalf("Migrate: %v", err)
	}
	return db
}

func seedUser(t *testing.T, db *sql.DB, id, name string) {
	t.Helper()
	if _, err := db.Exec(`INSERT INTO "user" (id, display_name) VALUES (?, ?)`, id, name); err != nil {
		t.Fatalf("seed user %s: %v", id, err)
	}
}

func seedTitle(t *testing.T, db *sql.DB, id string, tmdbID int, kind string) {
	t.Helper()
	_, err := db.Exec(
		`INSERT INTO title (id, tmdb_id, kind, display_title, release_year, synopsis, poster_path, added_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		id, tmdbID, kind, "Title "+id, 2020, "synopsis", "/p.jpg", "2026-04-18T00:00:00Z",
	)
	if err != nil {
		t.Fatalf("seed title %s: %v", id, err)
	}
}

func TestMigrate_CreatesContentTables(t *testing.T) {
	db := openMigratedDB(t)

	for _, want := range []string{"title", "library_entry", "rating"} {
		var name string
		err := db.QueryRow(`SELECT name FROM sqlite_master WHERE type='table' AND name=?`, want).Scan(&name)
		if err != nil {
			t.Errorf("table %q missing: %v", want, err)
		}
	}
}

func TestMigrate_TitleTmdbIdUnique(t *testing.T) {
	db := openMigratedDB(t)

	seedTitle(t, db, "t_1", 42, "movie")
	_, err := db.Exec(
		`INSERT INTO title (id, tmdb_id, kind, display_title, release_year, synopsis, poster_path, added_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		"t_2", 42, "movie", "dup", 2020, "x", "/p.jpg", "2026-04-18T00:00:00Z",
	)
	if err == nil {
		t.Fatal("duplicate tmdb_id accepted")
	}
}

func TestMigrate_TitleKindCheckRejectsBadValue(t *testing.T) {
	db := openMigratedDB(t)

	_, err := db.Exec(
		`INSERT INTO title (id, tmdb_id, kind, display_title, release_year, synopsis, poster_path, added_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		"t_bad", 99, "documentary", "x", 2020, "x", "/p.jpg", "2026-04-18T00:00:00Z",
	)
	if err == nil {
		t.Fatal("invalid kind accepted")
	}
}

func TestMigrate_TitlePosterPathNullable(t *testing.T) {
	db := openMigratedDB(t)

	_, err := db.Exec(
		`INSERT INTO title (id, tmdb_id, kind, display_title, release_year, synopsis, poster_path, added_at) VALUES (?, ?, ?, ?, ?, ?, NULL, ?)`,
		"t_noposter", 100, "movie", "x", 2020, "x", "2026-04-18T00:00:00Z",
	)
	if err != nil {
		t.Errorf("NULL poster_path rejected: %v", err)
	}
}

func TestMigrate_LibraryEntryTitleIdUnique(t *testing.T) {
	db := openMigratedDB(t)
	seedTitle(t, db, "t_1", 1, "movie")

	if _, err := db.Exec(
		`INSERT INTO library_entry (id, title_id, status, added_at, status_updated_at) VALUES (?, ?, ?, ?, ?)`,
		"le_1", "t_1", "want", "2026-04-18T00:00:00Z", "2026-04-18T00:00:00Z",
	); err != nil {
		t.Fatalf("first insert: %v", err)
	}
	_, err := db.Exec(
		`INSERT INTO library_entry (id, title_id, status, added_at, status_updated_at) VALUES (?, ?, ?, ?, ?)`,
		"le_2", "t_1", "watched", "2026-04-18T00:00:00Z", "2026-04-18T00:00:00Z",
	)
	if err == nil {
		t.Fatal("second library_entry for same title accepted")
	}
}

func TestMigrate_LibraryEntryStatusCheckRejectsBadValue(t *testing.T) {
	db := openMigratedDB(t)
	seedTitle(t, db, "t_1", 1, "movie")

	_, err := db.Exec(
		`INSERT INTO library_entry (id, title_id, status, added_at, status_updated_at) VALUES (?, ?, ?, ?, ?)`,
		"le_bad", "t_1", "maybe", "2026-04-18T00:00:00Z", "2026-04-18T00:00:00Z",
	)
	if err == nil {
		t.Fatal("invalid status accepted")
	}
}

func TestMigrate_RatingCompositeUnique(t *testing.T) {
	db := openMigratedDB(t)
	seedUser(t, db, "u_1", "Kira")
	seedTitle(t, db, "t_1", 1, "movie")

	if _, err := db.Exec(
		`INSERT INTO rating (id, title_id, user_id, score, note, rated_at) VALUES (?, ?, ?, ?, ?, ?)`,
		"r_1", "t_1", "u_1", 4, nil, "2026-04-18T00:00:00Z",
	); err != nil {
		t.Fatalf("first rating: %v", err)
	}
	_, err := db.Exec(
		`INSERT INTO rating (id, title_id, user_id, score, note, rated_at) VALUES (?, ?, ?, ?, ?, ?)`,
		"r_2", "t_1", "u_1", 5, nil, "2026-04-18T00:00:00Z",
	)
	if err == nil {
		t.Fatal("duplicate (title_id, user_id) accepted")
	}
}

func TestMigrate_RatingScoreCheck(t *testing.T) {
	db := openMigratedDB(t)
	seedUser(t, db, "u_1", "Kira")
	seedTitle(t, db, "t_1", 1, "movie")

	cases := []struct {
		name    string
		score   int
		wantErr bool
	}{
		{"zero ok", 0, false},
		{"five ok", 5, false},
		{"negative rejected", -1, true},
		{"above range rejected", 6, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			id := "r_" + tc.name
			_, err := db.Exec(
				`INSERT INTO rating (id, title_id, user_id, score, note, rated_at) VALUES (?, ?, ?, ?, ?, ?)`,
				id, "t_1", "u_1", tc.score, nil, "2026-04-18T00:00:00Z",
			)
			hasErr := err != nil
			if hasErr != tc.wantErr {
				t.Errorf("score=%d hasErr=%v, wantErr=%v (err=%v)", tc.score, hasErr, tc.wantErr, err)
			}
			// Clear the row so the composite-unique constraint doesn't leak into the next case.
			_, _ = db.Exec(`DELETE FROM rating WHERE id = ?`, id)
		})
	}
}

func TestMigrate_DeleteTitleCascadesEntryAndRatings(t *testing.T) {
	db := openMigratedDB(t)
	seedUser(t, db, "u_1", "Kira")
	seedUser(t, db, "u_2", "M")
	seedTitle(t, db, "t_1", 1, "movie")
	if _, err := db.Exec(
		`INSERT INTO library_entry (id, title_id, status, added_at, status_updated_at) VALUES (?, ?, ?, ?, ?)`,
		"le_1", "t_1", "want", "2026-04-18T00:00:00Z", "2026-04-18T00:00:00Z",
	); err != nil {
		t.Fatalf("insert entry: %v", err)
	}
	for i, u := range []string{"u_1", "u_2"} {
		if _, err := db.Exec(
			`INSERT INTO rating (id, title_id, user_id, score, note, rated_at) VALUES (?, ?, ?, ?, ?, ?)`,
			"r_"+u, "t_1", u, 3+i, nil, "2026-04-18T00:00:00Z",
		); err != nil {
			t.Fatalf("insert rating %s: %v", u, err)
		}
	}

	if _, err := db.Exec(`DELETE FROM title WHERE id = ?`, "t_1"); err != nil {
		t.Fatalf("delete title: %v", err)
	}

	var entryCount, ratingCount int
	if err := db.QueryRow(`SELECT COUNT(*) FROM library_entry WHERE title_id = ?`, "t_1").Scan(&entryCount); err != nil {
		t.Fatalf("count entry: %v", err)
	}
	if err := db.QueryRow(`SELECT COUNT(*) FROM rating WHERE title_id = ?`, "t_1").Scan(&ratingCount); err != nil {
		t.Fatalf("count rating: %v", err)
	}
	if entryCount != 0 || ratingCount != 0 {
		t.Errorf("cascade failed: entries=%d ratings=%d", entryCount, ratingCount)
	}
}

func TestMigrate_DeleteUserCascadesRatings(t *testing.T) {
	db := openMigratedDB(t)
	seedUser(t, db, "u_1", "Kira")
	seedTitle(t, db, "t_1", 1, "movie")
	if _, err := db.Exec(
		`INSERT INTO rating (id, title_id, user_id, score, note, rated_at) VALUES (?, ?, ?, ?, ?, ?)`,
		"r_1", "t_1", "u_1", 4, nil, "2026-04-18T00:00:00Z",
	); err != nil {
		t.Fatalf("insert rating: %v", err)
	}

	if _, err := db.Exec(`DELETE FROM "user" WHERE id = ?`, "u_1"); err != nil {
		t.Fatalf("delete user: %v", err)
	}

	var n int
	if err := db.QueryRow(`SELECT COUNT(*) FROM rating WHERE user_id = ?`, "u_1").Scan(&n); err != nil {
		t.Fatalf("count: %v", err)
	}
	if n != 0 {
		t.Errorf("ratings after cascade = %d, want 0", n)
	}
}

func TestMigrate_RatingFKRejectsOrphanTitle(t *testing.T) {
	db := openMigratedDB(t)
	seedUser(t, db, "u_1", "Kira")

	_, err := db.Exec(
		`INSERT INTO rating (id, title_id, user_id, score, note, rated_at) VALUES (?, ?, ?, ?, ?, ?)`,
		"r_1", "ghost_title", "u_1", 4, nil, "2026-04-18T00:00:00Z",
	)
	if err == nil {
		t.Fatal("rating with nonexistent title accepted")
	}
}

func TestMigrate_LibraryEntryFKRejectsOrphanTitle(t *testing.T) {
	db := openMigratedDB(t)

	_, err := db.Exec(
		`INSERT INTO library_entry (id, title_id, status, added_at, status_updated_at) VALUES (?, ?, ?, ?, ?)`,
		"le_1", "ghost_title", "want", "2026-04-18T00:00:00Z", "2026-04-18T00:00:00Z",
	)
	if err == nil {
		t.Fatal("library_entry with nonexistent title accepted")
	}
}

func TestMigrate_TitleSeriesKindAccepted(t *testing.T) {
	db := openMigratedDB(t)

	_, err := db.Exec(
		`INSERT INTO title (id, tmdb_id, kind, display_title, release_year, synopsis, poster_path, added_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		"t_s", 99, "series", "Show", 2019, "x", "/p.jpg", "2026-04-18T00:00:00Z",
	)
	if err != nil {
		t.Errorf("series kind rejected: %v", err)
	}
}
