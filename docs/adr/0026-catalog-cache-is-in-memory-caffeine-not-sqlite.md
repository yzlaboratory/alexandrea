# Catalog cache is in-memory (Caffeine), not SQLite-backed

The two-layer catalog cache (ADR 0007) is implemented as an **in-memory
Caffeine cache**, not a SQLite table. A redeploy cold-starts it: the next
request for any given entry or feed page pays one upstream call, exactly as
a first-ever request would.

## Why not SQLite, given the rest of the stack is SQLite-backed

The auth epic (#8) deliberately made sessions SQLite-backed (Spring Session
JDBC) specifically so a redeploy would not lose them — losing a session
kicks a real person out. That precedent could reasonably be assumed to
extend here. It doesn't, because the two cases differ in what a miss costs:

- ADR 0001 already commits to the cache being "load-bearing for UX latency,
  not for correctness" — an empty cache **must** still produce correct
  results by hitting upstream directly. A cold cache is therefore an
  explicitly designed-for path, not a degraded or exceptional one.
- The cost of a miss is one upstream call, re-paid at most once per
  `(provider, entry-or-feed-page)` per redeploy. Nothing is lost, corrupted,
  or user-visible beyond a slightly slower first load.
- In-memory means no schema, no write load against the same SQLite file the
  rest of the app's durable state lives in, and no migration to maintain
  for data that is explicitly disposable.

## Considered and rejected

- **SQLite-backed cache**, matching sessions and the rest of the persistence
  layer. Rejected: durability here buys nothing (ADR 0001's correctness
  guarantee doesn't depend on it) while adding schema, write load, and an
  eviction/TTL sweep competing with the auth cleanup job (ADR 0017) for the
  same database file.

## Consequences

- Every redeploy re-triggers a burst of upstream calls proportional to
  active traffic in the minutes after deploy — acceptable at personal-tracker
  scale, and within the same per-provider rate-limit budget ADR 0007 already
  reasons about for a cold cache.
- Cache size is bounded by Caffeine's own eviction policy (size/weight-based),
  not by a TTL sweep job — simpler than the token/rate-limit cleanup
  scheduler (ADR 0017), which exists only because *that* state is
  SQLite-backed and durable.
- If a future requirement makes catalog latency load-bearing for
  correctness (it currently is not, per ADR 0001), this decision should be
  revisited.
