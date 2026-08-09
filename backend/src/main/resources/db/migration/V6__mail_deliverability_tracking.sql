-- Backs the SES bounce/complaint feedback loop. Two tables, one migration:
-- they're both written together by the same SQS event handler and have no
-- independent lifecycle.

-- Recipients SES has told us are unreachable (hard bounce) or complained.
-- Decoupled from users rather than a column there: a bounced address doesn't
-- always correspond to a live user row (e.g. an in-flight email change, or an
-- AlreadyRegisteredMail target for an address that never signed up here).
CREATE TABLE unsendable_addresses (
    email       TEXT PRIMARY KEY,
    reason      TEXT NOT NULL,
    recorded_at TEXT NOT NULL
);

-- SQS is at-least-once delivery: a redelivered bounce/complaint notification
-- must not re-suppress (harmless) but, more importantly, must be detectable
-- as already-handled so a message that legitimately covers several
-- recipients isn't half-applied on retry. Keyed on the SES messageId plus
-- the specific recipient, since one notification can list more than one.
CREATE TABLE processed_ses_events (
    message_id   TEXT NOT NULL,
    recipient    TEXT NOT NULL,
    processed_at TEXT NOT NULL,
    PRIMARY KEY (message_id, recipient)
);
