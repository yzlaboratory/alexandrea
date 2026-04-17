# NEXT

Handoff note for the next session. Self-contained — readable cold.

## Where the repo is (2026-04-17)

- **5 product specs** in `specs/` — MVP shape is frozen.
- **5 ADRs** in `adr/` covering hosting, datastore, auth, TMDB integration, and frontend.
- **`OPEN-QUESTIONS.md`** tracks everything deferred.
- **No code.** Just specs + decisions.

## What to do first, in order

### 1. Close the small open items (user was mid-way through approving)

I presented the table below and the user went offline before confirming. Treat these as my recommendations; ask the user to confirm or override, then commit the picks into the relevant ADRs (update them in place) and move resolved items into `OPEN-QUESTIONS.md`'s *Resolved* section.

| Item | My recommended pick | Where it lives |
|---|---|---|
| Domain name | Ask the user — I can't pick taste | ADR 0001 Follow-ups |
| Backup destination | **Backblaze B2** (vendor diversity vs. Hetzner Storage Box) | ADR 0002 Follow-ups |
| Password length floor | **≥14 chars, no complexity rules** | ADR 0003 Follow-ups |
| Icon set | **Lucide** | ADR 0005 Follow-ups |
| Go hot-reloader | **`air`** (dev-only, not shipped) | ADR 0005 Follow-ups |
| Node at deploy time | **CI runs `pnpm build`; `pnpm` never on the VPS** | ADR 0005 Follow-ups |

### 2. Fix the stale entry in `OPEN-QUESTIONS.md`

The "CSRF library / pattern" bullet under *From ADR 0003* is obsolete — ADR 0005 resolved it (double-submit cookie middleware). Delete it from the ADR 0003 section; the existing "HTTP router — Resolved in ADR 0005" entry in *Resolved* already covers it. No new entry needed.

### 3. Leave the event-triggered items alone

These are correctly deferred and should **not** be touched until their trigger fires:

- Local poster caching (TMDB CDN flakiness)
- Postgres cut-over (>10 concurrent users, FTS need, or 2nd app server)
- TMDB call observability (when any metrics stack is adopted)
- Active-sessions admin UI (nice-to-have)
- Password-reset UI (3rd user arrives)
- TS type generation from Go structs (when drift bites)
- Env file delivery via CI (when CI/CD deploy ADR lands)
- PWA (if offline ever becomes a goal)
- Argon2id param tuning (needs the CX22 to measure on)
- TMDB attribution placement (needs first UI mock)

### 4. After decisions land — **start implementation**

The spec + 5 ADRs fully define the shape. No Phase 0 / Phase 1 / Phase 2 decomposition has been written this time (deliberately — MVP scope is small enough to not need it). The natural first slice:

1. **Repo layout**: `backend/` (Go) + `frontend/` (Vite/React/TS) + `deploy/` (systemd unit, Caddyfile, backup script). `.github/workflows/` for CI.
2. **Backend skeleton** (Go): `cmd/entlib/main.go`, `cmd/entlib/seed.go` (subcommand per ADR 0003), stdlib `net/http` router, `embed.FS` for migrations and the frontend `dist/`, SQLite via `modernc.org/sqlite` (ADR 0002), argon2id from `golang.org/x/crypto/argon2` (ADR 0003), systemd env loader, `/api/health` and `/api/me`.
3. **Flyway-equivalent**: `pressly/goose` migrations under `backend/db/migrations/`. V1 = `user`, plus `user_credential` + `session` from ADR 0003. TMDB-related columns in the product `title` table per `specs/04-data-model.md`.
4. **Frontend skeleton** (Vite/React/TS): login page, auth-gated shell, hello-world library view. Embedded via `backend/internal/web/assets.go` using `//go:embed`.
5. **Deploy artifacts**: `deploy/systemd/entlib.service`, `deploy/caddy/Caddyfile`, `deploy/backup/nightly-backup.sh`.
6. **GitHub Actions**: `ci.yml` (build + test) and `deploy.yml` (ssh + scp binary + systemctl restart).
7. **Runbook stubs**: `docs/runbooks/phase-0-provisioning.md`, `docs/runbooks/restore-from-backup.md`, `docs/runbooks/seed-users.md`.

Use worktrees (per `~/.claude/CLAUDE.md`). One worktree per meaningful slice, atomic commits, push without asking.

**Do not provision cloud infrastructure** (VPS, domain, B2 bucket, TMDB API key) from the agent side. That's a user task; stop and hand off at that boundary.

## Conventions to respect

- `~/.claude/CLAUDE.md` says: loose TDD, use worktrees, atomic commits with short-but-expressive messages, push without asking, excessive happy/unhappy/edge tests, prettier on the frontend.
- The `specs/` folder is prose — don't put JSON Schema / OpenAPI / test fixtures there.
- Every ADR Follow-up must also appear in `OPEN-QUESTIONS.md`. Resolved items move with a pointer, they don't disappear.

## Things the agent got wrong last time (don't repeat)

- **Do not use a shared in-memory SQLite cache** (`:memory:?cache=shared`) for tests — it leaks Flyway history across Spring contexts. File-per-context works. This bit me on the previous branch that's now deleted.
- **Don't forget Spring Data JDBC's optimistic-locking `@Version`** — pre-populated `@Id` triggers UPDATE, not INSERT. Moot for Go (no such framework) but keep the category of bug in mind: *any* ORM's "is this new?" heuristic is a gotcha.
- Previous Phase 0 attempt on branch `wt/phase-0-foundation` was deleted (local + remote). The orphan commit is `2ec8f06` if ever needed — reflog-recoverable for ~90 days.
