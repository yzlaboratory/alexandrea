# Local list rows persist a denormalized sort/filter snapshot

Each local list row — a **Watchlist** entry (#7) and a **Library** entry
(#4) — persists a denormalized snapshot of the catalog fields used as sort
and filter keys: **`title`**, **`release_date`**, and **`genres`** (stored in
the per-media-type filter vocabulary). This is in addition to the
`(external_provider, external_id, media_type)` reference and the user-owned
data (Rating, Completion Dates). It lets sorting, filtering, and pagination of
the user's own lists run as a pure local SQLite query that does **not** depend
on any upstream provider being reachable.

## Why

Unlike the catalog (#3), there is **no upstream "list my watchlist/library"
endpoint** to delegate to (cf. ADR 0018) — these lists are our own rows. To
order or filter them by `title`/`release_date`/`genre` without a local copy of
those keys, we would have to hydrate every entry's metadata from the cache (or
upstream) on open, then sort and paginate. That couples *browsing data the
user owns* to provider uptime: a cold cache plus a provider outage would make
your own library unsortable, and a cold list would fire a burst of upstream
calls (straight into ADR 0015's circuit breaker). Persisting the sort/filter
keys locally removes that coupling.

## What is and isn't snapshotted

- **Snapshotted:** `title`, `release_date`, `genres`. For Books, the snapshot
  stores the **resolved curated genres** (ADR 0013), so library genre
  filtering is a local set-membership test — not the provider-side
  `subject:(alias OR …)` query the *catalog* uses (ADR 0018).
- **Not snapshotted:** cover art, synopsis, external rating, and everything
  else remain cache/upstream-sourced per ADR 0001 / ADR 0007. A cold cover
  degrades to a placeholder while the row still sorts and filters correctly.

The snapshot is **not** a catalog mirror and **not** a source of truth — it is
a query index over data the user owns (their list membership), denormalized
for local sort/filter.

## Freshness

The snapshot is captured when the row is created (Completion for Library, add
for Watchlist) and refreshed opportunistically whenever the per-entry metadata
cache refreshes that entry. It therefore trails upstream within the same
staleness envelope as ADR 0007's 7-day TTL — acceptable for sort/filter keys.

## Relationship to ADR 0001

ADR 0001 says catalog metadata is never our source of truth and is fetched
live. This snapshot does not violate that: it is a denormalized query index,
refreshed from the same upstream, not an authoritative store. ADR 0001 carries
a pointer to this carve-out.

## Consequences

- Watchlist and Library schemas carry `title`, `release_date`, `genres`; the
  write path on Completion/add and on cache refresh must populate them.
- **ADR 0009 stays coherent in sorted lists.** An entry whose upstream was
  removed keeps its last-known snapshot, so it still sorts and filters
  correctly and renders with the "removed by <provider>" affordance — without
  the snapshot it would have no title to sort by and would fall out of ordered
  views.
- Genre vocabulary changes (ADR 0013 re-curation) require re-resolving the
  Books `genres` snapshot on the next cache refresh of each entry.
