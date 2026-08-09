# Catalog upstream failures fall through to stale cache; a per-provider circuit breaker bounds amplification

The catalog cache of ADR 0007 carries a uniform 7-day TTL with
lazy expiry on read. This ADR pins what happens when a cache miss
(or expired entry refresh) **fails** at the upstream provider —
TMDB, OpenLibrary, or IGDB — through a network error, a 5xx, a
rate-limit response, a malformed body, or a timeout.

## Behaviour

- **Stale-while-error.** If a cache miss fails upstream and the
  cache holds a previously-fetched row for the same `(provider,
  external_id, media_type)` — even if its TTL has expired — that
  stale row is served. A best-effort background refresh is queued
  but its outcome does not block the request. This applies to **both**
  cache layers of ADR 0007: a failed per-entry refresh serves the stale
  row, and a failed feed/query-page fetch with a warm-but-expired page
  serves that stale page (see #3).
- **Cold miss falls through to a user-facing error.** If there is
  no cached row at all, the request fails with the same generic
  *"this title is temporarily unavailable, try again in a moment"*
  affordance the user sees on any other transient failure. Empty
  rows are never fabricated.
- **Per-provider circuit breaker.** Each upstream provider has
  its own breaker. After **5 consecutive failures** the breaker
  opens for **60 seconds**, during which all calls to that
  provider short-circuit (no upstream request is issued; cached
  rows are served per the rule above; cold misses fail fast).
  After 60s the breaker enters half-open — the next call is
  allowed through as a probe. A successful probe closes the
  breaker; a failed probe re-opens it for another 60s.

The breaker is in-process state on the single EC2 instance
(ADR 0014) — a small map keyed by provider. No external
coordination, no Redis. On instance restart the breaker resets
closed.

## Why stale-while-error rather than hard fail

- **Most upstream errors are transient.** A TMDB 502 lasting 800ms
  during a user's catalog browse should not blank the grid.
- **The cache is already 7 days at steady state** (ADR 0007).
  Users implicitly accept that catalog data is *"≤7 days fresh."*
  Serving a row that is e.g. 9 days old during a transient blip
  extends the same envelope rather than introducing a new one.
- **Spec-level UX is unchanged.** No *"may be stale"* affordance
  is shown. The grid renders normally; the small fidelity gap is
  invisible to the user.

## Why a circuit breaker on top

- **Without a breaker, a flapping upstream amplifies.** Every
  cache-miss request reaches for the failing provider and waits
  for the timeout, multiplying user-visible latency by the failure
  count and burning the per-key rate-limit budget on retries that
  cannot succeed.
- **With a breaker, failures cost one timeout per 60s window.**
  All other concurrent requests serve cached data immediately
  (or fail fast on cold miss). The provider gets a 60s recovery
  window with no traffic from us.
- **It is essentially free under SQLite single-node.** The
  breaker is one in-memory atomic counter and one timestamp per
  provider. No persistence, no coordination.

## Why these specific numbers

- **5 failures before opening.** Below this, single-request
  flakes (one 502, one 504) would open the breaker for users
  who weren't affected. Above this, real outages take longer to
  protect against.
- **60s open duration.** Long enough to give the provider room
  to recover; short enough that the user re-trying after a coffee
  sip lands a probe and the system self-heals.
- **Half-open with one probe.** Standard breaker shape. Avoids
  thundering-herd retry on close.

These are tunable per provider if real ops data shows skew (IGDB
is harsher-rate-limited than TMDB). Tuning is a config change,
not an ADR change.

## Consequences

- **Stale-cache reads must be possible.** The cache layer's read
  path is *"return the row regardless of TTL; signal freshness
  separately."* The TTL-respecting path is the default; the
  stale-tolerating path is the fallback. ADR 0007's lazy-expiry
  rule remains accurate for the happy path.
- **Background refresh is best-effort.** A queued refresh that
  fails again does not retry indefinitely; it logs and yields.
  The next user-driven cache miss will re-attempt under the same
  rules.
- **Breaker state is observable in logs.** Each open / half-open
  / close transition emits a structured log line so operators can
  see provider health from CloudWatch (per ADR 0014's logging
  shape).
- **The breaker does not protect us from the user's own client.**
  A user spamming refresh during an upstream outage gets cached
  rows; the upstream is shielded. Rate-limiting our *clients*
  (separate from rate-limiting our *upstream calls*) is a
  different concern not covered here.
- **Every redeploy, not just instance replacement, cold-starts the
  cache.** The catalog cache is in-memory Caffeine (ADR 0026), not
  the SQLite file, so it carries no backup/restore path — a fresh
  process always starts empty. The breaker behaves identically on a
  cold cache as on a warm one — cold misses fail fast once the
  breaker opens — so this costs latency, not correctness.
