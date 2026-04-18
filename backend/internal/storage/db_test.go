package storage

import (
	"path/filepath"
	"testing"
)

func TestOpen_AppliesPragmas(t *testing.T) {
	dsn := filepath.Join(t.TempDir(), "test.sqlite")
	db, err := Open(dsn)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })

	cases := []struct {
		pragma string
		want   string
	}{
		{"journal_mode", "wal"},
		{"foreign_keys", "1"},
		{"synchronous", "1"}, // NORMAL = 1
	}
	for _, tc := range cases {
		var got string
		if err := db.QueryRow("PRAGMA " + tc.pragma).Scan(&got); err != nil {
			t.Fatalf("scan %s: %v", tc.pragma, err)
		}
		if got != tc.want {
			t.Errorf("PRAGMA %s = %q, want %q", tc.pragma, got, tc.want)
		}
	}
}

func TestOpen_InvalidPathFails(t *testing.T) {
	// A path inside a non-existent directory cannot be created.
	dsn := filepath.Join(t.TempDir(), "nope", "missing", "db.sqlite")
	db, err := Open(dsn)
	if err == nil {
		_ = db.Close()
		t.Fatal("Open: want error for unreachable path, got nil")
	}
}
