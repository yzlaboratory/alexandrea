-- Identity store and email-verification tokens (ADR 0021).
--
-- Alexandrea owns auth in-app: this is the foundational schema every later
-- auth slice (login, sticky media type, reset, change-email) reads and writes.
-- The full users column set is created now even though the signup/verify tracer
-- bullet only exercises email, password_hash and verified — the table is shared,
-- so adding columns later would mean another migration for state already known.
--
-- DDL is kept SQLite-compatible (ADR 0014): no SERIAL/BOOLEAN/TIMESTAMPTZ.
-- Timestamps are TEXT in ISO-8601 UTC; booleans are INTEGER 0/1.

CREATE TABLE users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,

    -- Email is the login identifier and is unique. Stored lower-cased by the
    -- application so uniqueness is case-insensitive without a SQLite collation.
    email         TEXT    NOT NULL UNIQUE,

    -- Argon2id hash only — the plaintext password is never stored (ADR 0021).
    -- The {argon2} prefix written by Spring's DelegatingPasswordEncoder lives
    -- inside this value.
    password_hash TEXT    NOT NULL,

    -- 0 = unverified (cannot reach protected surfaces), 1 = verified.
    verified      INTEGER NOT NULL DEFAULT 0,

    -- Sticky media type (CONTEXT.md): the User's last-used media type, remem-
    -- bered server-side. NULL means "never chose" and defaults to Movies at
    -- read time.
    last_media_type TEXT,

    created_at    TEXT    NOT NULL,
    updated_at    TEXT    NOT NULL
);

-- Single-use, expiring tokens issued to the real inbox owner (ADR 0014: every
-- token is 128-bit URL-safe CSPRNG). Only the email-verification kind exists
-- today; the table is shaped to also carry reset / email-change kinds via the
-- `kind` discriminator so those slices add rows, not tables.
CREATE TABLE auth_tokens (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,

    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Token kind, e.g. 'verification'. One active token per (user, kind):
    -- issuing a new one invalidates the prior (see the partial unique index).
    kind        TEXT    NOT NULL,

    -- The raw token is never stored — only its SHA-256 hash, so a database
    -- leak does not hand out usable links. The lookup hashes the presented
    -- token and matches on this column.
    token_hash  TEXT    NOT NULL UNIQUE,

    expires_at  TEXT    NOT NULL,

    -- NULL until the token is consumed; set to the consumption instant so an
    -- already-used link is rejected rather than re-activating an account.
    consumed_at TEXT,

    created_at  TEXT    NOT NULL
);

-- "One active token per (user, kind)" — enforced only over rows that are still
-- live (unconsumed). Consumed rows are kept for audit/idempotency and excluded
-- here so a fresh issue after consumption does not collide.
CREATE UNIQUE INDEX ux_auth_tokens_active_per_user_kind
    ON auth_tokens(user_id, kind)
    WHERE consumed_at IS NULL;
