-- +goose Up
CREATE TABLE title (
    id            TEXT PRIMARY KEY,
    tmdb_id       INTEGER NOT NULL UNIQUE,
    kind          TEXT NOT NULL CHECK(kind IN ('movie', 'series')),
    display_title TEXT NOT NULL,
    release_year  INTEGER NOT NULL,
    synopsis      TEXT NOT NULL,
    poster_path   TEXT,
    added_at      TEXT NOT NULL
);

CREATE TABLE library_entry (
    id                TEXT PRIMARY KEY,
    title_id          TEXT NOT NULL UNIQUE REFERENCES title(id) ON DELETE CASCADE,
    status            TEXT NOT NULL CHECK(status IN ('want', 'watching', 'watched', 'abandoned')),
    added_at          TEXT NOT NULL,
    status_updated_at TEXT NOT NULL
);

CREATE TABLE rating (
    id       TEXT PRIMARY KEY,
    title_id TEXT NOT NULL REFERENCES title(id) ON DELETE CASCADE,
    user_id  TEXT NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    score    INTEGER NOT NULL CHECK(score BETWEEN 0 AND 5),
    note     TEXT,
    rated_at TEXT NOT NULL,
    UNIQUE(title_id, user_id)
);

CREATE INDEX rating_user_id_idx ON rating(user_id);
CREATE INDEX rating_title_id_idx ON rating(title_id);

-- +goose Down
DROP INDEX IF EXISTS rating_title_id_idx;
DROP INDEX IF EXISTS rating_user_id_idx;
DROP TABLE rating;
DROP TABLE library_entry;
DROP TABLE title;
