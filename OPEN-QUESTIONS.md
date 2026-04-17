# Open Questions

Running list of decisions deferred or not yet started. Items graduate off this list when an ADR addresses them — resolved items stay here with a pointer ("Resolved in ADR 00XX, 2026-MM-DD") so the history of what was *deliberately* deferred is legible.

## From ADR 0001 — Hosting & Runtime

- **HTTP router.** Go stdlib (`net/http` 1.22+ has pattern-based routing), `chi`, or `echo`. Stdlib is the default instinct at this scope.
- **Server-rendered vs. SPA.** Either htmx + `html/template` (no separate frontend build, one binary serves HTML) or a React/TS SPA bundled into the binary. Scope is small enough that server-rendered is defensible; SPA only earns its weight if the interaction model demands it.
- **Domain name.** Nothing registered yet. `.de` vs `.app` vs something else.

## From ADR 0002 — Datastore

- **Backup destination.**
  - **Hetzner Storage Box** — same provider, low latency, ~€3.80/mo for 1 TB.
  - **Backblaze B2** — vendor diversity, ~$0.005/GB/mo (effectively free at our size).
- **Postgres cut-over criteria.** Suggested triggers we'd commit to in advance: more than ~10 concurrent users routinely, the need for full-text search beyond SQLite's FTS5, or wanting two app servers. Worth naming the thresholds before we hit them.

## From ADR 0003 — Auth

- **CSRF library / pattern.** Decided in the HTTP-router ADR rather than here.
- **Password length floor.** Suggested ≥14 chars; document when `docs/runbooks/seed-users.md` exists.
- **Argon2id parameter tuning.** Measure login latency on the CX22 once running; target 250–500 ms per verify.
- **Password-reset-via-ssh tolerance.** Fine at two users; revisit when a third arrives.
- **Active-sessions admin page** (view + revoke). Nice-to-have, not v0.

## From ADR 0004 — TMDB Integration

- **Local poster caching.** Triggered by TMDB CDN flakiness, hardening privacy, or a PWA push. Needs its own ADR when the trigger fires.
- **TMDB call observability.** A small counter (total / 4xx / 5xx / retried) once we adopt any metrics stack.
- **Env file delivery.** Move `/etc/entlib/env` into the CI/CD flow (GitHub Actions secret → env file at deploy time) when that lands.
- **Attribution placement.** Confirm TMDB attribution copy/logo position once the first UI mock exists.

## ADRs not yet started

- **Frontend shape.** Same decision as ADR 0001's server-rendered-vs-SPA follow-up; listed here so it doesn't get lost.
- **HTTP router** (also an ADR 0001 follow-up). Stdlib vs. `chi`. CSRF pattern rides on this.

## Resolved

- **Auth mechanism** — Resolved in ADR 0003 (2026-04-17): password-per-user with argon2id and server-side sessions.
- **TMDB usage** — Resolved in ADR 0004 (2026-04-17): server-proxied calls with API key in a systemd env file; posters hot-linked from TMDB's CDN with `poster_path` stored, not full URLs.

## Infra tasks (not ADR-shaped, but open)

- Provision the Hetzner CX22 and a domain.
- Create `/var/lib/entlib/` and a dedicated service user during VPS setup.
- Write `docs/runbooks/restore-from-backup.md` once ops actually exists.
- Set up GitHub Actions deploy workflow after the 3rd manual deploy (per ADR 0001).

## Conventions

- Every deferred item in an ADR's *Follow-ups* section gets mirrored here, so this file is the single place to look.
- When an ADR resolves an item, mark it here with "Resolved in ADR 00XX (YYYY-MM-DD)" rather than deleting — the record of what was deferred and for how long is useful.
