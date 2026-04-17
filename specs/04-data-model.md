# Data Model

Four entities. No derived state cached as columns. No audit rows.

## `User`

Two rows, seeded at deploy time.

- `id` — opaque identifier
- `display_name` — "Kira", "M" (or whatever the deploy chooses)

No email, no avatar, no locale, no theme, no auth-provider fields. The authentication method is a deployment concern (shared secret, hardcoded cookie, IP allowlist) and is deliberately outside the product spec.

## `Title`

- `id` — opaque, locally generated
- `tmdb_id` — integer, unique
- `kind` — `movie` or `series`
- `display_title` — TMDB English name
- `release_year` — integer
- `synopsis` — TMDB overview text
- `poster_url` — TMDB poster URL
- `added_at` — when it was first looked up and stored locally

## `LibraryEntry`

Exactly one row per title present in the library.

- `id` — opaque
- `title_id` — foreign key
- `status` — one of `want`, `watching`, `watched`, `abandoned`
- `added_at` — timestamp
- `status_updated_at` — timestamp

There is no `added_by_user_id`. Either user can add, either can mutate; authorship of an entry is not recorded.

## `Rating`

At most one row per `(title_id, user_id)` pair.

- `id` — opaque
- `title_id` — foreign key
- `user_id` — foreign key
- `score` — integer 0–5
- `note` — text, nullable
- `rated_at` — timestamp (create-or-last-edit; no separate edit history)

## Cardinality

- A `Title` has 0 or 1 `LibraryEntry`. A title with no library entry exists only because someone searched for it and nobody cleaned it up — it is effectively a cache row.
- A `Title` has 0, 1, or 2 `Rating` rows.
- A `User` has 0..N `Rating` rows.

## Derived values (computed on read, never stored)

- **Average score for a title** — arithmetic mean of its ratings when two exist; the single score when one exists; undefined when none exist.

That's the only derived value. Everything else is a direct column read.
