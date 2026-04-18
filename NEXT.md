# NEXT

Handoff note for the next session. Self-contained — readable cold.

## Where the repo is (2026-04-18, end-of-day)

**The original 9-slice plan is fully merged to `main`.** What's next is *product* work: the library itself, which means wiring up TMDB and building the title / library / rating surfaces.

- **5 product specs** in `specs/` — MVP shape frozen.
- **5 ADRs** in `adr/` — follow-ups that were within agent authority are resolved and logged in `OPEN-QUESTIONS.md`.
- **Backend (Go).** Single binary `entlib` with `serve` and `seed`. Argon2id + SQLite sessions + CSRF double-submit all working end-to-end.
- **Frontend (React/Vite/TS).** Scaffold present, embedded into the binary via `//go:embed`. Login works in the browser against the real backend; home page shows `Hello, {display_name}` + logout. Everything library-shaped is still stubbed.
- **Deploy artifacts.** `deploy/systemd/entlib.service`, `deploy/caddy/Caddyfile`, `deploy/backup/nightly-backup.sh`.
- **CI.** `.github/workflows/ci.yml` green on `main` (test + build). `.github/workflows/deploy.yml` is `workflow_dispatch`-only; will be promoted to push-to-main after the 3rd clean manual deploy (per ADR 0001).
- **Runbooks.** `docs/runbooks/{phase-0-provisioning,restore-from-backup,seed-users}.md`.

### Commit log since the last handoff

```
7c98e41 Add seed-users runbook
97af08f Add restore-from-backup runbook
a9218fa Add phase-0 VPS provisioning runbook
86850d1 Add GitHub Actions deploy workflow (workflow_dispatch)
cd07c59 Add GitHub Actions CI workflow
57930fe Add nightly SQLite backup script per ADR 0002
8a61316 Add Caddyfile for TLS-terminating reverse proxy
80b729a Add systemd unit for entlib per ADR 0001
c984944 Skip asset-404 router test when frontend isn't built
0b73c33 Add Makefile for combined frontend+backend build
ebbf73f Serve embedded SPA via //go:embed with SPA-fallback routing
36243a4 Scaffold React/TS/Vite frontend under frontend/ per ADR 0005
```

### Slice progress

| # | Slice                                                | Status |
|---|------------------------------------------------------|--------|
| 1 | Go backend skeleton + /api/health                    | done — merged |
| 2 | SQLite + goose migrations (V1 user)                  | done — merged |
| 3 | Auth migrations (V2 user_credential + session)       | done — merged |
| 4 | Content migrations (V3 title, library_entry, rating) | done — merged |
| 5 | Auth handlers + `entlib seed` subcommand             | done — merged |
| 6 | Frontend skeleton + embed.FS wiring                  | done — merged |
| 7 | Deploy artifacts                                     | done — merged |
| 8 | GitHub Actions CI + deploy                           | done — merged |
| 9 | Runbook stubs                                        | done — merged |

## How the backend works now

Unchanged from the previous handoff — repeated here so the file stays self-contained.

- **Binary:** `backend/cmd/entlib/` with `main.go`, `serve.go`, `seed.go`.
- **Env vars:** `ENTLIB_DB_DSN` (default `./entlib.sqlite`), `ENTLIB_HTTP_ADDR` (default `:8080`), `ENTLIB_COOKIE_SECURE` (default `true`). Also `TMDB_API_KEY` in `/etc/entlib/env` once TMDB wiring lands.
- **DB:** `modernc.org/sqlite` (pure-Go), pragmas from ADR 0002, schema at V3 via goose. Tables: `user`, `user_credential`, `session`, `title`, `library_entry`, `rating`.
- **Seeding:** `entlib seed --display-name … --username … --password '…'`. Password floor ≥14 chars. **Username becomes `user.id`** — the one invariant not spelled out in ADR 0003.
- **Auth handlers:** `POST /login`, `POST /logout`, `GET /api/me`, `GET /api/health`. Constant-time dummy-hash for unknown usernames (`backend/internal/httpx/auth.go`).
- **CSRF:** Double-submit per ADR 0005. Middleware fires *before* the mux — so POSTs to GET-only paths return 403, not 405. Dedicated test covers this.
- **Frontend↔backend:** Vite dev server on `:5173` proxies `/api`, `/login`, `/logout` to `:8080`. In production the Go binary serves the embedded SPA directly.

## How the frontend works now

- `frontend/src/api/client.ts` — `apiRequest` / `apiJson` with automatic CSRF header on mutating calls; `ApiError(status)` for error surfacing.
- `frontend/src/auth/useAuth.ts` — `useMe` (TanStack Query against `/api/me`, 401 → null), `useLogin`, `useLogout`.
- `frontend/src/App.tsx` — React Router with `/login`, `/` (auth-gated `HomePage`), and `*` → `/` redirect.
- `frontend/src/pages/HomePage.tsx` — the current placeholder saying "Your library is empty." This is what the next slice replaces with a real library view.
- Tailwind v4 via `@tailwindcss/postcss`. Lucide for icons. Zustand is installed but not yet used (only needed once there's cross-component client state).

## What to do first, in order

Product work. No more infra until something needs it.

### Slice 10 — TMDB server-side proxy

**Goal:** the Go backend can call TMDB on behalf of an authenticated user and return the minimum needed to render a search result grid and a title page.

Per ADR 0004:

- Read `TMDB_API_KEY` at startup; fail fast if missing *unless* `ENTLIB_TMDB_OPTIONAL=true` (for local dev without a key).
- `GET /api/search?q=<query>` → proxy to `/search/multi`, filter to `movie` + `tv`, return `{tmdb_id, kind, title, year, poster_path, overview}`.
- `GET /api/tmdb/title/:kind/:id` → proxy to `/movie/:id` or `/tv/:id`, return the same shape plus overview.
- No poster caching — `poster_path` only; frontend builds `https://image.tmdb.org/t/p/w500/{poster_path}`.
- Rate-limit handling: TMDB allows ~40/sec. Single-user app, but add a simple `singleflight`-style dedupe for identical queries within ~250 ms so a debounced search box doesn't stampede.
- Reuse existing auth gate — non-authenticated callers get 401.

**Testing:** hit a recorded fixture (httptest.Server standing in for TMDB) in unit tests, plus one `//go:build integration` test that hits real TMDB when `TMDB_API_KEY` is present in the environment.

### Slice 11 — Title + library CRUD

- `title` table already exists (V3). Add handlers:
  - `POST /api/titles` — idempotent "add from TMDB": if a row with the same `tmdb_id` + `kind` exists, return it; else fetch from TMDB and insert.
  - `GET /api/titles/:id` — read a local title row.
- Library:
  - `GET /api/library` — list entries with joined title data; optional `?status=want,watching` filter (default).
  - `POST /api/library` — body `{title_id, status}` where status defaults to `want`.
  - `PATCH /api/library/:id` — change status.
  - `DELETE /api/library/:id` — hard delete. No soft delete per spec.
- Frontend: replace the stub `HomePage` with a library view (default filter: `want`+`watching`), a search box calling `/api/search`, and a title page with the "add to library" + status controls.

### Slice 12 — Ratings

- `POST /api/library/:id/rating` — `{score: 0-5, note?: string}`. Overwrites any existing rating by the same user.
- `DELETE /api/library/:id/rating` — deletes the current user's rating only.
- Rating a non-watched entry implicitly transitions it to `watched` (per spec `03-ratings.md`).
- Frontend: two side-by-side rating widgets on the title page, average on the `watched` tab.

After slice 12, the MVP's user-facing behavior is complete. What comes after is provisioning, not code: follow `docs/runbooks/phase-0-provisioning.md`, do the first manual deploy, run a restore drill, then promote the deploy workflow from `workflow_dispatch` to push-to-main.

## What is **not** the agent's job

- **Provisioning the VPS, registering the domain, creating the Hetzner Storage Box, getting the TMDB API key.** All user-hands-on. Stop at the boundary and print a checklist.
- **Rewriting already-merged ADRs.** They can be superseded, not edited in place.

## Housekeeping worth doing

- Two minor cleanup candidates noted in the CI run:
  - Node.js 20 deprecation warning on GitHub-maintained actions. Fixable by setting `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24=true` at the workflow level, or waiting for action authors to ship Node 24-capable versions. Non-urgent; warning only until 2026-09-16.
- The four Spring Boot worktrees (`auth-baseline`, `auth-google-oidc`, `auth-polish`, `backend-skeleton`) are vestigial from a previous architecture. Still safe to delete (`git worktree remove` + `git branch -D`) — not touched so far because they were not in scope.

## Conventions to respect (unchanged)

- `~/.claude/CLAUDE.md`: loose TDD, use worktrees, atomic commits with short-but-expressive messages, push without asking, excessive happy/unhappy/edge tests, prettier on the frontend.
- `specs/` is prose — don't put JSON Schema / OpenAPI / test fixtures there.
- Every ADR Follow-up must also appear in `OPEN-QUESTIONS.md`. Resolved items move with a pointer; they don't disappear.

## Things the agent got wrong (updated)

- (Still true) Tried to FF-merge a branch into itself from the branch's worktree — silently no-op'd. Fix: run `git merge --ff-only <branch>` from the `main` worktree, not the slice worktree.
- (Still true) `go mod tidy` prunes requires for packages nothing imports yet; `go get` alone isn't enough if no source file references the package.
- (Still true) Attempted to re-Write `main.go` without Read-ing it first — got "File has not been read yet" from the tooling; used Read then Write. Be ready for that pattern when overwriting existing files.
- (Still true) Don't shared-cache in-memory SQLite for tests. Use `t.TempDir()` per test.
- (This session) `backend/internal/httpx/asset_404_cache_test.go` was written without a `FrontendBuilt()` gate, so `go test ./...` failed on fresh checkouts where only `dist/.gitkeep` exists. Fixed in `c984944`. Pattern for the next person: any test that depends on the embedded SPA must branch on `web.FrontendBuilt()`.
