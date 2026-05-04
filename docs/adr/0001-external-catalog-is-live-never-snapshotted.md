# External catalog is live, never snapshotted

We do not store catalog metadata (title, cover, release date, genres,
external rating) in our own database. Catalog data is fetched live from
the external providers (TMDB for Movies/TV, OpenLibrary for Books, IGDB
for Games) through a cache layer whose only job is to avoid upstream
rate limits — it is not a source of truth and is not relied on for
durability.

Local rows reference Catalog Items by
`(external_provider, external_id, media_type)`. When an upstream
provider removes a Catalog Item, the corresponding local row is removed
and a foreign-key cascade drops all dependent watchlist entries,
library entries, ratings, and completion dates. There is no archival
or tombstone path.

## Consequences

- **Freshness for free.** Covers, titles, and synopses always reflect
  the upstream provider; we never display stale metadata.
- **No catalog schema, no sync jobs, no bulk-copy storage.**
- **Upstream removals are destructive.** If TMDB removes a film, every
  user who had rated or shelved it loses that data. We accept this in
  exchange for the simplifications above.
- **The cache is load-bearing for UX latency, not for correctness.**
  An empty cache must still produce correct results by hitting the
  upstream directly.
