package main

import (
	"bytes"
	"path/filepath"
	"testing"

	"github.com/yzlaboratory/entertainment-library/backend/internal/auth"
	"github.com/yzlaboratory/entertainment-library/backend/internal/storage"
)

func TestRunSeed_CreatesUserAndCredential(t *testing.T) {
	dsn := filepath.Join(t.TempDir(), "t.sqlite")
	t.Setenv("ENTLIB_DB_DSN", dsn)

	var out bytes.Buffer
	args := []string{"--display-name", "Kira", "--username", "kira", "--password", "correcthorsebatterystaple"}
	if err := runSeed(args, &out); err != nil {
		t.Fatalf("runSeed: %v", err)
	}

	db, err := storage.Open(dsn)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })

	var name string
	if err := db.QueryRow(`SELECT display_name FROM "user" WHERE id = ?`, "kira").Scan(&name); err != nil {
		t.Fatalf("select user: %v", err)
	}
	if name != "Kira" {
		t.Errorf("display_name = %q, want Kira", name)
	}

	var hash string
	if err := db.QueryRow(`SELECT password_hash FROM user_credential WHERE user_id = ?`, "kira").Scan(&hash); err != nil {
		t.Fatalf("select credential: %v", err)
	}
	if err := auth.VerifyPassword("correcthorsebatterystaple", hash); err != nil {
		t.Errorf("verify: %v", err)
	}
}

func TestRunSeed_RotatesExistingPassword(t *testing.T) {
	dsn := filepath.Join(t.TempDir(), "t.sqlite")
	t.Setenv("ENTLIB_DB_DSN", dsn)

	var out bytes.Buffer
	first := []string{"--display-name", "Kira", "--username", "kira", "--password", "firstpasswordlongenough"}
	if err := runSeed(first, &out); err != nil {
		t.Fatalf("first: %v", err)
	}

	second := []string{"--display-name", "Kira Updated", "--username", "kira", "--password", "secondpasswordlongenough"}
	if err := runSeed(second, &out); err != nil {
		t.Fatalf("second: %v", err)
	}

	db, err := storage.Open(dsn)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })

	var name, hash string
	if err := db.QueryRow(`SELECT display_name FROM "user" WHERE id = ?`, "kira").Scan(&name); err != nil {
		t.Fatalf("select user: %v", err)
	}
	if name != "Kira Updated" {
		t.Errorf("display_name = %q, want updated", name)
	}

	if err := db.QueryRow(`SELECT password_hash FROM user_credential WHERE user_id = ?`, "kira").Scan(&hash); err != nil {
		t.Fatalf("select credential: %v", err)
	}
	if err := auth.VerifyPassword("firstpasswordlongenough", hash); err == nil {
		t.Error("old password still verifies after rotation")
	}
	if err := auth.VerifyPassword("secondpasswordlongenough", hash); err != nil {
		t.Errorf("new password does not verify: %v", err)
	}
}

func TestRunSeed_RequiresAllFlags(t *testing.T) {
	t.Setenv("ENTLIB_DB_DSN", filepath.Join(t.TempDir(), "t.sqlite"))
	cases := [][]string{
		{"--display-name", "Kira", "--username", "kira"},
		{"--display-name", "Kira", "--password", "a-long-enough-pw"},
		{"--username", "kira", "--password", "a-long-enough-pw"},
		{},
	}
	for i, args := range cases {
		var out bytes.Buffer
		if err := runSeed(args, &out); err == nil {
			t.Errorf("case %d %v: want error, got nil", i, args)
		}
	}
}

func TestRunSeed_RejectsShortPassword(t *testing.T) {
	t.Setenv("ENTLIB_DB_DSN", filepath.Join(t.TempDir(), "t.sqlite"))
	var out bytes.Buffer
	args := []string{"--display-name", "Kira", "--username", "kira", "--password", "tooshort"}
	if err := runSeed(args, &out); err == nil {
		t.Error("short password accepted")
	}
}

func TestRunSeed_RunsMigrationsOnFreshDB(t *testing.T) {
	dsn := filepath.Join(t.TempDir(), "brand-new.sqlite")
	t.Setenv("ENTLIB_DB_DSN", dsn)
	var out bytes.Buffer
	args := []string{"--display-name", "M", "--username", "m", "--password", "another-long-password"}
	if err := runSeed(args, &out); err != nil {
		t.Fatalf("runSeed: %v", err)
	}

	db, err := storage.Open(dsn)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })

	for _, table := range []string{"user", "user_credential", "session", "title"} {
		var name string
		if err := db.QueryRow(`SELECT name FROM sqlite_master WHERE type='table' AND name=?`, table).Scan(&name); err != nil {
			t.Errorf("table %q missing: %v", table, err)
		}
	}
}
