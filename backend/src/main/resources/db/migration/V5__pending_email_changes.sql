-- Pending email changes get their own table rather than a new_email column on
-- auth_tokens: no other token kind needs a payload, and ADR 0021 already names
-- "pending-email-change" as its own conceptual token table alongside
-- verification/reset. Shape mirrors auth_tokens (V1) — hashed token, expiry,
-- single-use via a partial unique index — plus the target address the token
-- carries until it's consumed.

CREATE TABLE pending_email_changes (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,

    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- The address users.email switches to once this token is consumed.
    new_email   TEXT    NOT NULL,

    token_hash  TEXT    NOT NULL UNIQUE,

    expires_at  TEXT    NOT NULL,

    consumed_at TEXT,

    created_at  TEXT    NOT NULL
);

-- One live pending change per user: a new request invalidates the prior (see
-- ux_auth_tokens_active_per_user_kind in V1 for the same pattern; this table
-- serves only one kind, so there's no discriminator column to key on).
CREATE UNIQUE INDEX ux_pending_email_changes_active_per_user
    ON pending_email_changes(user_id)
    WHERE consumed_at IS NULL;
