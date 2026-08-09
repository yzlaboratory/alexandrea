-- Fixed-window rate-limit counters (ADR 0021, ADR 0024). One row per bucket
-- key; a key already encodes its scope and dimension, e.g.
-- 'login:email:owner@example.com' or 'mail:ip:203.0.113.5', so the table
-- stays generic across every rate-limited endpoint rather than growing a
-- column per scope.

CREATE TABLE rate_limit_buckets (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,

    bucket_key        TEXT    NOT NULL UNIQUE,

    -- The start of the fixed window this count belongs to; a request in a
    -- later window resets the count rather than adding to it.
    window_started_at TEXT    NOT NULL,

    attempt_count     INTEGER NOT NULL DEFAULT 0
);
