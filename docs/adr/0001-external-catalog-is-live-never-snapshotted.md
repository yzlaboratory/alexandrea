# External catalog is live, never snapshotted

We do not store catalog metadata (title, cover, release date, genres,
external rating) in our own database. Catalog data is fetched live from
the external providers (TMDB for Movies/TV, OpenLibrary for Books, IGDB
for Games) through a cache layer whose only job is to avoid upstream
rate limits — it is not a source of truth and is not relied on for
durability.

**Carve-out (ADR 0019):** local list rows (Watchlist, Library) persist a
denormalized snapshot of the sort/filter keys (`title`, `release_date`,
`genres`) so the user's own lists sort and filter without upstream. That
snapshot is a query index refreshed from the same upstream — not a source of
truth and not a catalog mirror — so the "never stored" rule still holds for
everything else and for authoritativeness.

Local rows reference Catalog Items by
`(external_provider, external_id, media_type)`. The cascade-on-removal
behaviour originally written into this ADR was **retracted by ADR 0009**
— upstream removal no longer destroys local rows. Instead, the local
row is preserved and the entry's display flips to a per-provider
"removed by <provider>" affordance until the upstream restores it.
The user's Watchlist or Library membership, Rating, and Completion
Dates are preserved through any upstream change.

## Consequences

- **Freshness for free.** Covers, titles, and synopses always reflect
  the upstream provider; we never display stale metadata.
- **No catalog schema, no sync jobs, no bulk-copy storage.**
- **Upstream removals are not destructive** (per ADR 0009). If TMDB
  removes a film, the user keeps their Rating and Completion Dates;
  the entry simply renders with a "Removed by TMDB" affordance until
  the provider restores it (if ever).
- **The cache is load-bearing for UX latency, not for correctness.**
  An empty cache must still produce correct results by hitting the
  upstream directly.
