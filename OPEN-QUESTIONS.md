# Open Questions

Running list of decisions deferred or not yet started. Items graduate off this list when an ADR addresses them — resolved items stay here with a pointer ("Resolved in ADR 00XX, 2026-MM-DD") so the history of what was *deliberately* deferred is legible.

## From ADR 0001 — Hosting & Runtime

- **Domain name.** Nothing registered yet. `.de` vs `.app` vs something else.

## From ADR 0002 — Datastore

- **Postgres cut-over criteria.** Suggested triggers we'd commit to in advance: more than ~10 concurrent users routinely, the need for full-text search beyond SQLite's FTS5, or wanting two app servers. Worth naming the thresholds before we hit them.

## From ADR 0003 — Auth

- **Argon2id parameter tuning.** Measure login latency on the CX22 once running; target 250–500 ms per verify.
- **Password-reset-via-ssh tolerance.** Fine at two users; revisit when a third arrives.
- **Active-sessions admin page** (view + revoke). Nice-to-have, not v0.

## From ADR 0004 — TMDB Integration

- **Local poster caching.** Triggered by TMDB CDN flakiness, hardening privacy, or a PWA push. Needs its own ADR when the trigger fires.
- **TMDB call observability.** A small counter (total / 4xx / 5xx / retried) once we adopt any metrics stack.
- **Env file delivery.** Move `/etc/entlib/env` into the CI/CD flow (GitHub Actions secret → env file at deploy time) when that lands.
- **Attribution placement.** Confirm TMDB attribution copy/logo position once the first UI mock exists.

## From ADR 0005 — Frontend

- **TypeScript type generation from Go API.** Hand-written types for now; add generation if drift becomes painful.
- **PWA / Service Worker** strategy, if offline support ever becomes a goal.

## Resolved

- **Auth mechanism** — Resolved in ADR 0003 (2026-04-17): password-per-user with argon2id and server-side sessions.
- **TMDB usage** — Resolved in ADR 0004 (2026-04-17): server-proxied calls with API key in a systemd env file; posters hot-linked from TMDB's CDN with `poster_path` stored, not full URLs.
- **Frontend shape** — Resolved in ADR 0005 (2026-04-17): React + TypeScript + Vite SPA embedded into the Go binary via `embed.FS`.
- **HTTP router** — Resolved in ADR 0005 (2026-04-17): Go stdlib `net/http` (1.22+ pattern routing), no router library. CSRF via double-submit cookie middleware.
- **Backup destination** — Resolved 2026-04-18 (session pick, recorded in ADR 0002 Follow-ups): Hetzner Storage Box.
- **Password length floor** — Resolved 2026-04-18 (session pick, recorded in ADR 0003 Follow-ups): ≥14 chars, no complexity rules.
- **Icon set** — Resolved 2026-04-18 (session pick, recorded in ADR 0005 Follow-ups): Lucide.
- **Go hot-reloader for dev** — Resolved 2026-04-18 (session pick, recorded in ADR 0005): `air`, dev-only.
- **Node at deploy time** — Resolved 2026-04-18 (session pick, recorded in ADR 0005 Follow-ups): CI runs `pnpm build`; Node/pnpm never installed on the VPS.

## Infra tasks (not ADR-shaped, but open)

- Provision the Hetzner CX22 and a domain.
- Create `/var/lib/entlib/` and a dedicated service user during VPS setup.
- Write `docs/runbooks/restore-from-backup.md` once ops actually exists.
- Set up GitHub Actions deploy workflow after the 3rd manual deploy (per ADR 0001).

## Conventions

- Every deferred item in an ADR's *Follow-ups* section gets mirrored here, so this file is the single place to look.
- When an ADR resolves an item, mark it here with "Resolved in ADR 00XX (YYYY-MM-DD)" rather than deleting — the record of what was deferred and for how long is useful.
