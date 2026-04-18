package auth

import (
	"crypto/rand"
	"database/sql"
	"encoding/base64"
	"errors"
	"fmt"
	"time"
)

const (
	SessionLifetime = 90 * 24 * time.Hour
	touchInterval   = 24 * time.Hour
)

var (
	ErrSessionNotFound = errors.New("session: not found")
	ErrSessionExpired  = errors.New("session: expired")
)

type Session struct {
	ID         string
	UserID     string
	CreatedAt  time.Time
	LastSeenAt time.Time
	ExpiresAt  time.Time
}

type Clock interface {
	Now() time.Time
}

type realClock struct{}

func (realClock) Now() time.Time { return time.Now().UTC() }

func DefaultClock() Clock { return realClock{} }

type SessionStore struct {
	db    *sql.DB
	clock Clock
}

func NewSessionStore(db *sql.DB) *SessionStore {
	return &SessionStore{db: db, clock: DefaultClock()}
}

func newSessionStoreWithClock(db *sql.DB, c Clock) *SessionStore {
	return &SessionStore{db: db, clock: c}
}

func (s *SessionStore) Mint(userID string) (Session, error) {
	idBytes := make([]byte, 32)
	if _, err := rand.Read(idBytes); err != nil {
		return Session{}, fmt.Errorf("random id: %w", err)
	}
	id := base64.RawURLEncoding.EncodeToString(idBytes)

	now := s.clock.Now()
	sess := Session{
		ID:         id,
		UserID:     userID,
		CreatedAt:  now,
		LastSeenAt: now,
		ExpiresAt:  now.Add(SessionLifetime),
	}

	_, err := s.db.Exec(
		`INSERT INTO session (id, user_id, created_at, last_seen_at, expires_at) VALUES (?, ?, ?, ?, ?)`,
		sess.ID, sess.UserID,
		formatTS(sess.CreatedAt), formatTS(sess.LastSeenAt), formatTS(sess.ExpiresAt),
	)
	if err != nil {
		return Session{}, fmt.Errorf("insert session: %w", err)
	}
	return sess, nil
}

func (s *SessionStore) Load(id string) (Session, error) {
	var sess Session
	var created, lastSeen, expires string
	err := s.db.QueryRow(
		`SELECT id, user_id, created_at, last_seen_at, expires_at FROM session WHERE id = ?`,
		id,
	).Scan(&sess.ID, &sess.UserID, &created, &lastSeen, &expires)
	if errors.Is(err, sql.ErrNoRows) {
		return Session{}, ErrSessionNotFound
	}
	if err != nil {
		return Session{}, fmt.Errorf("query session: %w", err)
	}

	if sess.CreatedAt, err = parseTS(created); err != nil {
		return Session{}, fmt.Errorf("parse created_at: %w", err)
	}
	if sess.LastSeenAt, err = parseTS(lastSeen); err != nil {
		return Session{}, fmt.Errorf("parse last_seen_at: %w", err)
	}
	if sess.ExpiresAt, err = parseTS(expires); err != nil {
		return Session{}, fmt.Errorf("parse expires_at: %w", err)
	}

	if !s.clock.Now().Before(sess.ExpiresAt) {
		_, _ = s.db.Exec(`DELETE FROM session WHERE id = ?`, id)
		return Session{}, ErrSessionExpired
	}
	return sess, nil
}

func (s *SessionStore) Touch(sess Session) (Session, error) {
	now := s.clock.Now()
	if now.Sub(sess.LastSeenAt) < touchInterval {
		return sess, nil
	}
	newExpires := now.Add(SessionLifetime)
	_, err := s.db.Exec(
		`UPDATE session SET last_seen_at = ?, expires_at = ? WHERE id = ?`,
		formatTS(now), formatTS(newExpires), sess.ID,
	)
	if err != nil {
		return sess, fmt.Errorf("touch session: %w", err)
	}
	sess.LastSeenAt = now
	sess.ExpiresAt = newExpires
	return sess, nil
}

func (s *SessionStore) Delete(id string) error {
	_, err := s.db.Exec(`DELETE FROM session WHERE id = ?`, id)
	return err
}

func formatTS(t time.Time) string { return t.UTC().Format(time.RFC3339Nano) }
func parseTS(s string) (time.Time, error) {
	return time.Parse(time.RFC3339Nano, s)
}
