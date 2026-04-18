package auth

import (
	"database/sql"
	"errors"
	"path/filepath"
	"testing"
	"time"

	"github.com/yzlaboratory/entertainment-library/backend/internal/storage"
)

type fakeClock struct{ now time.Time }

func (c *fakeClock) Now() time.Time { return c.now }

func newTestDB(t *testing.T) *sql.DB {
	t.Helper()
	dsn := filepath.Join(t.TempDir(), "test.sqlite")
	db, err := storage.Open(dsn)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })
	if err := storage.Migrate(db); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	if _, err := db.Exec(`INSERT INTO "user" (id, display_name) VALUES (?, ?)`, "kira", "Kira"); err != nil {
		t.Fatalf("seed user: %v", err)
	}
	return db
}

func TestSessionStore_MintInsertsRow(t *testing.T) {
	db := newTestDB(t)
	store := NewSessionStore(db)

	sess, err := store.Mint("kira")
	if err != nil {
		t.Fatalf("mint: %v", err)
	}
	if sess.ID == "" {
		t.Error("session ID is empty")
	}
	if sess.UserID != "kira" {
		t.Errorf("user id = %q, want kira", sess.UserID)
	}
	if !sess.ExpiresAt.After(sess.CreatedAt) {
		t.Error("expires_at is not after created_at")
	}

	var count int
	if err := db.QueryRow(`SELECT COUNT(*) FROM session WHERE id = ?`, sess.ID).Scan(&count); err != nil {
		t.Fatalf("count: %v", err)
	}
	if count != 1 {
		t.Errorf("row count = %d, want 1", count)
	}
}

func TestSessionStore_MintIDsAreDistinct(t *testing.T) {
	db := newTestDB(t)
	store := NewSessionStore(db)

	seen := map[string]bool{}
	for i := 0; i < 10; i++ {
		sess, err := store.Mint("kira")
		if err != nil {
			t.Fatalf("mint %d: %v", i, err)
		}
		if seen[sess.ID] {
			t.Fatalf("duplicate session ID: %q", sess.ID)
		}
		seen[sess.ID] = true
	}
}

func TestSessionStore_LoadReturnsSession(t *testing.T) {
	db := newTestDB(t)
	store := NewSessionStore(db)

	minted, err := store.Mint("kira")
	if err != nil {
		t.Fatalf("mint: %v", err)
	}
	loaded, err := store.Load(minted.ID)
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	if loaded.UserID != "kira" {
		t.Errorf("user id = %q, want kira", loaded.UserID)
	}
	if !loaded.ExpiresAt.Equal(minted.ExpiresAt) {
		t.Errorf("expires_at = %v, want %v", loaded.ExpiresAt, minted.ExpiresAt)
	}
}

func TestSessionStore_LoadUnknownIDReturnsNotFound(t *testing.T) {
	db := newTestDB(t)
	store := NewSessionStore(db)

	_, err := store.Load("does-not-exist")
	if !errors.Is(err, ErrSessionNotFound) {
		t.Errorf("err = %v, want ErrSessionNotFound", err)
	}
}

func TestSessionStore_LoadExpiredReturnsExpiredAndSweeps(t *testing.T) {
	db := newTestDB(t)
	clock := &fakeClock{now: time.Date(2026, 4, 18, 12, 0, 0, 0, time.UTC)}
	store := newSessionStoreWithClock(db, clock)

	sess, err := store.Mint("kira")
	if err != nil {
		t.Fatalf("mint: %v", err)
	}

	// Advance time past the 90-day lifetime.
	clock.now = clock.now.Add(SessionLifetime + time.Minute)

	_, err = store.Load(sess.ID)
	if !errors.Is(err, ErrSessionExpired) {
		t.Errorf("err = %v, want ErrSessionExpired", err)
	}

	var count int
	if err := db.QueryRow(`SELECT COUNT(*) FROM session WHERE id = ?`, sess.ID).Scan(&count); err != nil {
		t.Fatalf("count: %v", err)
	}
	if count != 0 {
		t.Errorf("expired session not swept: count = %d", count)
	}
}

func TestSessionStore_TouchExtendsAfterInterval(t *testing.T) {
	db := newTestDB(t)
	clock := &fakeClock{now: time.Date(2026, 4, 18, 12, 0, 0, 0, time.UTC)}
	store := newSessionStoreWithClock(db, clock)

	sess, err := store.Mint("kira")
	if err != nil {
		t.Fatalf("mint: %v", err)
	}
	originalExpires := sess.ExpiresAt

	clock.now = clock.now.Add(touchInterval + time.Minute)

	touched, err := store.Touch(sess)
	if err != nil {
		t.Fatalf("touch: %v", err)
	}
	if !touched.ExpiresAt.After(originalExpires) {
		t.Errorf("expires_at = %v, want after %v", touched.ExpiresAt, originalExpires)
	}
}

func TestSessionStore_TouchSkipsWhenWithinInterval(t *testing.T) {
	db := newTestDB(t)
	clock := &fakeClock{now: time.Date(2026, 4, 18, 12, 0, 0, 0, time.UTC)}
	store := newSessionStoreWithClock(db, clock)

	sess, err := store.Mint("kira")
	if err != nil {
		t.Fatalf("mint: %v", err)
	}
	originalExpires := sess.ExpiresAt

	clock.now = clock.now.Add(touchInterval / 2)

	touched, err := store.Touch(sess)
	if err != nil {
		t.Fatalf("touch: %v", err)
	}
	if !touched.ExpiresAt.Equal(originalExpires) {
		t.Errorf("expires_at changed within interval: got %v, want %v", touched.ExpiresAt, originalExpires)
	}
}

func TestSessionStore_DeleteRemovesRow(t *testing.T) {
	db := newTestDB(t)
	store := NewSessionStore(db)

	sess, err := store.Mint("kira")
	if err != nil {
		t.Fatalf("mint: %v", err)
	}
	if err := store.Delete(sess.ID); err != nil {
		t.Fatalf("delete: %v", err)
	}

	_, err = store.Load(sess.ID)
	if !errors.Is(err, ErrSessionNotFound) {
		t.Errorf("err after delete = %v, want ErrSessionNotFound", err)
	}
}

func TestSessionStore_DeleteMissingIsNoop(t *testing.T) {
	db := newTestDB(t)
	store := NewSessionStore(db)

	if err := store.Delete("never-existed"); err != nil {
		t.Errorf("delete of missing id returned: %v", err)
	}
}
