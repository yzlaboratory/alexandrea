-- The foundational auth schema every later slice (login, sticky media type,
-- reset, change-email) reads and writes. The full users column set is created
-- now even though the signup/verify tracer bullet only exercises email,
-- password_hash and verified — the table is shared, so adding columns later
-- would mean another migration for state already known.
--
-- DDL is kept SQLite-compatible: no SERIAL/BOOLEAN/TIMESTAMPTZ. Timestamps are
-- TEXT in ISO-8601 UTC; booleans are INTEGER 0/1.

CREATE TABLE users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,

    -- The login identifier: unique, and stored lower-cased by the application.
    email         TEXT    NOT NULL UNIQUE,

    -- Stores the Argon2id hash; the {argon2} prefix written by Spring's
    -- DelegatingPasswordEncoder is part of the value.
    password_hash TEXT    NOT NULL,

    -- 0 = unverified, 1 = verified.
    verified      INTEGER NOT NULL DEFAULT 0,

    -- Sticky media type (CONTEXT.md). NULL means "never chose" and defaults to
    -- Movies at read time.
    last_media_type TEXT,

    created_at    TEXT    NOT NULL,
    updated_at    TEXT    NOT NULL
);

-- Only the email-verification kind exists today; the table is shaped to also
-- carry reset / email-change kinds via the `kind` discriminator, so those
-- slices add rows, not tables.
CREATE TABLE auth_tokens (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,

    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Token kind, e.g. 'verification'. One active token per (user, kind):
    -- issuing a new one invalidates the prior (see the partial unique index).
    kind        TEXT    NOT NULL,

    -- The token's SHA-256 hash, not the raw value; the lookup hashes the
    -- presented token and matches on this column.
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
