-- +goose Up
CREATE TABLE user_credential (
    user_id       TEXT PRIMARY KEY REFERENCES "user"(id) ON DELETE CASCADE,
    password_hash TEXT NOT NULL,
    updated_at    TEXT NOT NULL
);

CREATE TABLE session (
    id           TEXT PRIMARY KEY,
    user_id      TEXT NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    created_at   TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    expires_at   TEXT NOT NULL
);

CREATE INDEX session_user_id_idx ON session(user_id);
CREATE INDEX session_expires_at_idx ON session(expires_at);

-- +goose Down
DROP INDEX IF EXISTS session_expires_at_idx;
DROP INDEX IF EXISTS session_user_id_idx;
DROP TABLE session;
DROP TABLE user_credential;
