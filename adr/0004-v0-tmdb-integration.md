# ADR 0004: v0 TMDB Integration

- **Status:** Accepted
- **Date:** 2026-04-17
- **Deciders:** Kira
- **Supersedes:** —
- **Superseded by:** —
- **Related:** ADR 0001, ADR 0002, `specs/01-titles.md`

## Context

`specs/01-titles.md` names TMDB as the sole source of title metadata: *"All title metadata comes from [TMDB](https://www.themoviedb.org). No other providers, no cross-reference IDs, no Wikidata."* The same spec commits to fetching title data **once on add** and not refreshing it.

That leaves a handful of open technical questions:

- Where does the API key live?
- What does the request path look like (direct from the browser vs. server-proxied)?
- How do we handle TMDB's rate limit and occasional 5xx?
- What do we do with posters — hot-link TMDB's CDN, proxy them, or cache them locally?
- How do we satisfy TMDB's attribution requirement?

This ADR answers all of them.

## Decision

### API key

Stored in an env var `TMDB_API_KEY` loaded via `systemd EnvironmentFile=/etc/entlib/env`. The file is readable only by the service user. The key is never exposed to the browser.

Rotating the key is: edit `/etc/entlib/env`, `systemctl restart entlib`. No secret manager.

### Call shape

**All TMDB calls are server-side.** The frontend only talks to our own endpoints:

- `GET /api/search?q=...` — proxies `GET /search/multi` on TMDB, filters out `media_type: person`, returns only `movie` and `tv` results shaped as our own JSON (fields per `specs/01-titles.md`).
- `GET /api/titles/:id` — reads the local `title` row; never calls TMDB at request time.
- Adding a title (behind a mutation endpoint, routing TBD): fetches `/movie/:id` or `/tv/:id` once, copies the required fields into a new `title` row, returns the row.

Direct client-side calls to `api.themoviedb.org` are explicitly disallowed — they'd leak the API key.

### Data copied on add

Per `specs/01-titles.md` we store the minimum: `tmdb_id`, `kind`, `display_title`, `release_year`, `synopsis`, `poster_path`.

We store the **relative `poster_path`** (e.g. `/oRjpOUqco1pzxNeS9uggdlSbtHp.jpg`), not a fully-qualified URL. The base URL and size are chosen at render time. This lets us switch CDN strategies (below) with a single config change.

### Posters — hot-link TMDB's CDN in v0

Rendered URL is:

```
https://image.tmdb.org/t/p/{size}{poster_path}
```

Default sizes: `w342` for library thumbnails, `w500` for the title detail page. The base URL, size-map, and `poster_path` prefix are centralized in one config so we can flip to local proxying later without touching templates.

No poster is stored on the VPS. Disk stays small; the SQLite backup (ADR 0002) remains "one file."

### Rate limiting & retries

TMDB's free-tier limit is ~40 requests / 10 seconds — at our write rate we will never approach it. Still, the server-side client implements:

- Exponential backoff on `429` and `5xx` responses — base 200 ms, factor 2, 3 retries max (200 / 400 / 800 ms).
- A 10-second overall request timeout per call. Beyond that, surface an error to the user.
- No client-side token bucket — unnecessary at our scale.

### Attribution

Every page footer carries *"Data and images provided by [TMDB](https://www.themoviedb.org)"* plus TMDB's logo, per TMDB's free-tier attribution requirement.

### Language & region

Hardcoded `language=en-US`, `region=US` on every TMDB call. English-only is a product constraint (`specs/00-overview.md` *Scope*). Revisit if we ever expand.

### Privacy / logging

Search queries are **not logged** (they can reveal private taste). Only the numeric `tmdb_id` of a successful add is logged at INFO; queries that return results stay out of logs.

## Rationale

- **Env var over secret manager.** One key, one VPS, one systemd unit. Vault / SSM / SOPS would be theater at this scope.
- **Server-proxied over direct-from-browser.** The only correct way to keep the API key private. As a bonus it gives us one place to add caching or rewrite shape later.
- **Hot-link over local cache.** Cheapest thing that works. TMDB's CDN is reliable and public; caching posters costs ~50 KB/title, adds a disk concern, and bloats the "one file" backup story from ADR 0002. If TMDB CDN becomes unreliable or TOS changes, swap to a local `/posters/:id.jpg` handler in one commit.
- **Store `poster_path`, not the URL.** CDN base URL changes have happened before (image.tmdb.org vs. themoviedb.org/t/p) — storing the path means the change is a config edit, not a data migration.
- **Minimal retry policy.** 3 retries with backoff covers the usual transient hiccup; anything worse is better surfaced than papered over.

## Alternatives considered

- **Local poster cache on disk.** Download and serve locally. Pros: TMDB CDN independence, no referer leak, consistent latency. Cons: ~50 KB/title disk usage, complicates backup strategy, ~100 lines of Go. Deferred to a follow-up ADR — can adopt without migration.
- **Direct browser calls to TMDB with a "public-read" API key.** Any API key we ship to the browser is compromised; TMDB's terms also disallow this for hosted apps. Rejected outright.
- **A Go TMDB client library.** Adds a dependency and an abstraction for something we can do in ~30 lines of `net/http`. Rejected.
- **Secret manager (Vault, AWS SSM, sops).** Overkill for one key on one VPS. Rejected.
- **Caching search results.** At two users, the same search happens ~never. Even at 10 users, TMDB-side caching makes this redundant. Rejected.
- **Scheduled metadata refresh** (re-fetch title fields periodically). `specs/01-titles.md` explicitly says no — the library is read more than it's resynced. Rejected.

## Consequences

### Positive
- API key never leaves the VPS.
- Disk footprint stays trivial; backup story from ADR 0002 unchanged.
- One place (our server-side TMDB client) owns rate-limit handling, retries, and error mapping.
- Switching CDN strategy later is a one-file change.

### Negative
- **Image availability is TMDB's problem.** If their CDN has a bad day, we render broken posters. Accepted.
- **Browsers leak a Referer to TMDB** when loading posters. They see our domain; they don't see our user. Low concern.
- **No offline posters.** If we ever wanted an offline PWA mode, we'd need local caching first.
- **One shared API key.** A compromise means rotating via the VPS, then all sessions keep working (the key isn't in cookies).

### Follow-ups
- Decide whether to promote local poster caching (new ADR). Triggers: TMDB CDN flakiness, privacy stance hardening, or a PWA push.
- Decide observability for TMDB calls — right now we log nothing beyond successful adds; a simple counter (total / 4xx / 5xx / retried) might be worth adding when we adopt any metrics stack.
- Move the env file from `/etc/entlib/env` into whatever CI/CD story lands (GitHub Actions secret → env file at deploy time).
- Confirm the TMDB attribution copy/logo placement when the first UI mock exists.

## Sources

- [TMDB API v3 documentation](https://developer.themoviedb.org/docs)
- [TMDB attribution requirements](https://www.themoviedb.org/documentation/api/terms-of-use)
- [TMDB image base URL & sizes](https://developer.themoviedb.org/docs/image-basics)
- [systemd `EnvironmentFile=`](https://www.freedesktop.org/software/systemd/man/systemd.exec.html#EnvironmentFile=)
