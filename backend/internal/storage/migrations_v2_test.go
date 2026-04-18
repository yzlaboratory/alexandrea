package storage

import (
	"path/filepath"
	"testing"
)

func openMigrated(t *testing.T) (dsn string) {
	t.Helper()
	dsn = filepath.Join(t.TempDir(), "test.sqlite")
	db, err := Open(dsn)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })
	if err := Migrate(db); err != nil {
		t.Fatalf("Migrate: %v", err)
	}
	return dsn
}

func TestMigrate_CreatesUserCredentialTable(t *testing.T) {
	dsn := openMigrated(t)
	db, err := Open(dsn)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })

	var name string
	err = db.QueryRow(`SELECT name FROM sqlite_master WHERE type='table' AND name='user_credential'`).Scan(&name)
	if err != nil {
		t.Fatalf("user_credential not found: %v", err)
	}
}

func TestMigrate_CreatesSessionTable(t *testing.T) {
	dsn := openMigrated(t)
	db, err := Open(dsn)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })

	var name string
	err = db.QueryRow(`SELECT name FROM sqlite_master WHERE type='table' AND name='session'`).Scan(&name)
	if err != nil {
		t.Fatalf("session not found: %v", err)
	}
}

func TestMigrate_SessionIndexesExist(t *testing.T) {
	dsn := openMigrated(t)
	db, err := Open(dsn)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })

	rows, err := db.Query(`SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='session'`)
	if err != nil {
		t.Fatalf("query indexes: %v", err)
	}
	defer rows.Close()

	got := map[string]bool{}
	for rows.Next() {
		var n string
		if err := rows.Scan(&n); err != nil {
			t.Fatalf("scan: %v", err)
		}
		got[n] = true
	}
	for _, want := range []string{"session_user_id_idx", "session_expires_at_idx"} {
		if !got[want] {
			t.Errorf("missing index %q (have %v)", want, got)
		}
	}
}

func TestMigrate_UserCredentialFKRejectsOrphan(t *testing.T) {
	dsn := openMigrated(t)
	db, err := Open(dsn)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })

	_, err = db.Exec(
		`INSERT INTO user_credential (user_id, password_hash, updated_at) VALUES (?, ?, ?)`,
		"ghost", "x", "2026-04-18T00:00:00Z",
	)
	if err == nil {
		t.Fatal("insert user_credential for nonexistent user: want FK error, got nil")
	}
}

func TestMigrate_SessionFKRejectsOrphan(t *testing.T) {
	dsn := openMigrated(t)
	db, err := Open(dsn)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })

	_, err = db.Exec(
		`INSERT INTO session (id, user_id, created_at, last_seen_at, expires_at) VALUES (?, ?, ?, ?, ?)`,
		"s_x", "ghost", "2026-04-18T00:00:00Z", "2026-04-18T00:00:00Z", "2026-07-18T00:00:00Z",
	)
	if err == nil {
		t.Fatal("insert session for nonexistent user: want FK error, got nil")
	}
}

func TestMigrate_DeleteUserCascadesToCredentialAndSessions(t *testing.T) {
	dsn := openMigrated(t)
	db, err := Open(dsn)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })

	if _, err := db.Exec(`INSERT INTO "user" (id, display_name) VALUES (?, ?)`, "u_1", "Kira"); err != nil {
		t.Fatalf("insert user: %v", err)
	}
	if _, err := db.Exec(
		`INSERT INTO user_credential (user_id, password_hash, updated_at) VALUES (?, ?, ?)`,
		"u_1", "hash", "2026-04-18T00:00:00Z",
	); err != nil {
		t.Fatalf("insert credential: %v", err)
	}
	for _, id := range []string{"s_a", "s_b"} {
		if _, err := db.Exec(
			`INSERT INTO session (id, user_id, created_at, last_seen_at, expires_at) VALUES (?, ?, ?, ?, ?)`,
			id, "u_1", "2026-04-18T00:00:00Z", "2026-04-18T00:00:00Z", "2026-07-18T00:00:00Z",
		); err != nil {
			t.Fatalf("insert session %s: %v", id, err)
		}
	}

	if _, err := db.Exec(`DELETE FROM "user" WHERE id = ?`, "u_1"); err != nil {
		t.Fatalf("delete user: %v", err)
	}

	var credCount, sessCount int
	if err := db.QueryRow(`SELECT COUNT(*) FROM user_credential WHERE user_id = ?`, "u_1").Scan(&credCount); err != nil {
		t.Fatalf("count credential: %v", err)
	}
	if err := db.QueryRow(`SELECT COUNT(*) FROM session WHERE user_id = ?`, "u_1").Scan(&sessCount); err != nil {
		t.Fatalf("count session: %v", err)
	}
	if credCount != 0 {
		t.Errorf("user_credential rows after cascade = %d, want 0", credCount)
	}
	if sessCount != 0 {
		t.Errorf("session rows after cascade = %d, want 0", sessCount)
	}
}

func TestMigrate_UserCredentialRequiresNotNull(t *testing.T) {
	dsn := openMigrated(t)
	db, err := Open(dsn)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })

	if _, err := db.Exec(`INSERT INTO "user" (id, display_name) VALUES (?, ?)`, "u_2", "M"); err != nil {
		t.Fatalf("insert user: %v", err)
	}

	_, err = db.Exec(
		`INSERT INTO user_credential (user_id, password_hash, updated_at) VALUES (?, NULL, ?)`,
		"u_2", "2026-04-18T00:00:00Z",
	)
	if err == nil {
		t.Fatal("NULL password_hash accepted")
	}
}

func TestMigrate_SessionPrimaryKeyUnique(t *testing.T) {
	dsn := openMigrated(t)
	db, err := Open(dsn)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })

	if _, err := db.Exec(`INSERT INTO "user" (id, display_name) VALUES (?, ?)`, "u_3", "Kira"); err != nil {
		t.Fatalf("insert user: %v", err)
	}
	for i, id := range []string{"dup", "dup"} {
		_, err := db.Exec(
			`INSERT INTO session (id, user_id, created_at, last_seen_at, expires_at) VALUES (?, ?, ?, ?, ?)`,
			id, "u_3", "2026-04-18T00:00:00Z", "2026-04-18T00:00:00Z", "2026-07-18T00:00:00Z",
		)
		if i == 0 && err != nil {
			t.Fatalf("first insert: %v", err)
		}
		if i == 1 && err == nil {
			t.Fatal("duplicate session id accepted")
		}
	}
}
