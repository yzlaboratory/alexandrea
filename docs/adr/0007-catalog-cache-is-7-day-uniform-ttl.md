# Catalog cache is a 7-day uniform TTL with no stale-while-revalidate

The rate-limit-only cache committed to in ADR 0001 needs a freshness
policy. We use a **uniform 7-day TTL** for both metadata (title,
cover, synopsis, genres, external rating) and presence (the
`present` / `removed` / `redirected_to` / `transient_failure`
outcome from the provider adapter, per ADR 0003). On a cache miss
we fetch upstream synchronously; we do **not** serve stale entries
while revalidating in the background.

The cache has **two coexisting layers**, both under this 7-day TTL:

- a **per-entry metadata cache** keyed by
  `(provider, external_id, media_type)`, backing individual title
  lookups — the detail overlay (#6) and the rendering of local
  Watchlist/Library rows that store only the external id (#2, #4, #7); and
- a **feed/query page cache** keyed by
  `(provider, media_type, feed/query, filters, sort, page)`, backing the
  browse listings (#3).

Both are first-class — a cached feed page is **not** a substitute for the
per-entry rows it references, and neither layer is optional. This two-layer
cache is what makes most browse pages within a session cache hits, and it
is the granularity ADR 0015's stale-while-error operates on.

## Why 7 days

- The product is a personal tracker, not a news source. Users do
  not expect cover art, synopses, or genre tags to update in
  real time.
- A 7-day TTL keeps the upstream rate-limit budget roughly bounded
  by the active user count — most pages within a session are cache
  hits, and re-visits within a week never hit upstream.
- The lazy-removal cascade in ADR 0003 requires two `removed`
  outcomes. With a 7-day TTL the worst-case horizon from actual
  upstream removal to local cascade is about 14 days (one cycle
  to mark `pending_removal`, one more to fire the cascade). This
  is acceptable — the user holding a stale row for two weeks
  costs nothing while no one looks, which matches the spirit of
  the lazy-detection ADR.
- Metadata corrections that genuinely matter (an upstream provider
  fixes a misspelled author or replaces a wrong cover) propagate
  within a typical monthly user cadence; they are not silently
  invisible forever.

## Why no stale-while-revalidate

- Stale-while-revalidate would let the UX feel instant on every
  access, but it makes ADR 0003's lazy-detection mechanism fuzzy:
  which call counts as "the access" — the synchronous read that
  returned stale, or the background refresh? We want one answer,
  not two.
- The synchronous cache-miss cost is ~one upstream call per entry
  per week per user, which is well under any provider's free-tier
  budget for the user counts a personal-tracker app realistically
  reaches.

## Why one TTL, not two

- A separate, shorter TTL for presence (so removals are detected
  faster) was considered. Rejected because: (a) the user has
  explicitly accepted weeks of detection lag as fine, and (b) two
  TTLs is two cache key shapes, two invalidation paths, and two
  things a future engineer can break.

## Consequences

- **Metadata is up to 7 days stale.** A poster swap or synopsis
  rewrite upstream takes up to a week to appear. Acceptable.
- **Removal cascades take up to ~14 days worst case** from real
  upstream removal to local data loss. Inside the "even a week
  later is fine" tolerance set when this was decided.
- **The cache is bounded by entries-touched-in-the-last-7-days,**
  not by user count or library size. Operationally tractable.
- **Adapters must be deterministic per upstream response.** A
  flaky network blip that returns garbage must produce
  `transient_failure` (not silently fall through to `removed`),
  or ADR 0003's two-confirmation rule degrades.
- **No background revalidation jobs exist** for the catalog
  cache. Anyone tempted to add one to "smooth out the UX" must
  read this ADR and ADR 0003 first; the smoothness is not free,
  it costs the crispness of removal detection.
