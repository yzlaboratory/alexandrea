package storage

import (
	"path/filepath"
	"testing"
)

func TestMigrate_CreatesUserTable(t *testing.T) {
	dsn := filepath.Join(t.TempDir(), "test.sqlite")
	db, err := Open(dsn)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })

	if err := Migrate(db); err != nil {
		t.Fatalf("Migrate: %v", err)
	}

	var name string
	err = db.QueryRow(`SELECT name FROM sqlite_master WHERE type='table' AND name='user'`).Scan(&name)
	if err != nil {
		t.Fatalf("user table not found: %v", err)
	}
	if name != "user" {
		t.Errorf("table name = %q, want %q", name, "user")
	}
}

func TestMigrate_UserTableHasExpectedColumns(t *testing.T) {
	dsn := filepath.Join(t.TempDir(), "test.sqlite")
	db, err := Open(dsn)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })

	if err := Migrate(db); err != nil {
		t.Fatalf("Migrate: %v", err)
	}

	rows, err := db.Query(`PRAGMA table_info("user")`)
	if err != nil {
		t.Fatalf("table_info: %v", err)
	}
	defer rows.Close()

	cols := map[string]bool{}
	for rows.Next() {
		var cid int
		var name, ctype string
		var notnull, pk int
		var dflt any
		if err := rows.Scan(&cid, &name, &ctype, &notnull, &dflt, &pk); err != nil {
			t.Fatalf("scan: %v", err)
		}
		cols[name] = true
	}
	for _, want := range []string{"id", "display_name"} {
		if !cols[want] {
			t.Errorf("missing column %q (have %v)", want, cols)
		}
	}
}

func TestMigrate_IsIdempotent(t *testing.T) {
	dsn := filepath.Join(t.TempDir(), "test.sqlite")
	db, err := Open(dsn)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })

	if err := Migrate(db); err != nil {
		t.Fatalf("first Migrate: %v", err)
	}
	if err := Migrate(db); err != nil {
		t.Fatalf("second Migrate: %v", err)
	}
}

func TestMigrate_InsertAndReadUser(t *testing.T) {
	dsn := filepath.Join(t.TempDir(), "test.sqlite")
	db, err := Open(dsn)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })

	if err := Migrate(db); err != nil {
		t.Fatalf("Migrate: %v", err)
	}

	if _, err := db.Exec(`INSERT INTO "user" (id, display_name) VALUES (?, ?)`, "u_001", "Kira"); err != nil {
		t.Fatalf("insert: %v", err)
	}

	var name string
	if err := db.QueryRow(`SELECT display_name FROM "user" WHERE id = ?`, "u_001").Scan(&name); err != nil {
		t.Fatalf("select: %v", err)
	}
	if name != "Kira" {
		t.Errorf("display_name = %q, want %q", name, "Kira")
	}
}

func TestMigrate_DisplayNameNotNullEnforced(t *testing.T) {
	dsn := filepath.Join(t.TempDir(), "test.sqlite")
	db, err := Open(dsn)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })

	if err := Migrate(db); err != nil {
		t.Fatalf("Migrate: %v", err)
	}

	_, err = db.Exec(`INSERT INTO "user" (id, display_name) VALUES (?, NULL)`, "u_002")
	if err == nil {
		t.Fatal("insert with NULL display_name: want error, got nil")
	}
}
