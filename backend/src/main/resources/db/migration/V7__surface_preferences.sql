-- One shared preference store across all four Surfaces (ADR 0025): a typed
-- sort_key/sort_direction pair plus an opaque JSON filters blob the store
-- never validates — each surface's own service validates on read/write.
-- The (user_id, surface, media_type) key shape lets any surface use this
-- same table without a schema change.

CREATE TABLE surface_preferences (
    user_id        INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- e.g. 'catalog', 'watchlist', 'library', 'shares' (CONTEXT.md's Surface).
    surface        TEXT    NOT NULL,

    media_type     TEXT    NOT NULL,

    sort_key       TEXT,

    sort_direction TEXT,

    -- Opaque JSON filter map; the store never parses or validates it — see
    -- ADR 0025.
    filters        TEXT,

    updated_at     TEXT    NOT NULL,

    PRIMARY KEY (user_id, surface, media_type)
);
