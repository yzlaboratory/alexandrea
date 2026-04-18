# NEXT

Handoff note for the next session. Self-contained — readable cold.

## Where the repo is (2026-04-18)

- **5 product specs** in `specs/` — MVP shape frozen.
- **5 ADRs** in `adr/` — all follow-ups that were within agent authority are resolved and logged in `OPEN-QUESTIONS.md`.
- **Backend in Go** — single binary `entlib` with `serve` and `seed` subcommands. Auth pipeline works end-to-end over real HTTP (argon2id + SQLite sessions + CSRF double-submit).
- **No frontend yet.** React/Vite scaffold is the next natural slice.
- **No deploy artifacts yet.** Nothing in `deploy/` or `.github/`.

### Commit log since the last handoff

```
0b73453 Add CSRF double-submit middleware per ADR 0005
2b4bbc0 Add /login, /logout, and /api/me handlers
e569bce Add entlib seed subcommand and dispatch in main
772dc31 Add server-side session store backed by SQLite
898228c Add argon2id password hashing per ADR 0003
1ba2c87 V3 migration: title, library_entry, rating tables
059d9a7 V2 migration: user_credential and session tables
e80dcd3 Wire SQLite + goose; V1 migration creates user table
693d3e2 Scaffold Go backend with stdlib router and /api/health
2db20af Resolve five deferred picks from ADRs 0002/0003/0005
```

### Slice progress (from the original plan)

| # | Slice                                                     | Branch                          | Status |
|---|-----------------------------------------------------------|---------------------------------|--------|
| 1 | Go backend skeleton + /api/health                         | `go-backend-skeleton`           | done — merged |
| 2 | SQLite + goose migrations (V1 user)                       | `slice-2-sqlite-migrations`     | done — merged |
| 3 | Auth migrations (V2 user_credential + session)            | `slice-3-auth-migrations`       | done — merged |
| 4 | Content migrations (V3 title, library_entry, rating)      | `slice-4-content-migrations`    | done — merged |
| 5 | Auth handlers + `entlib seed` subcommand                  | `slice-5-auth`                  | done — merged |
| 6 | Frontend skeleton + embed.FS wiring                       | —                               | **next** |
| 7 | Deploy artifacts (systemd/Caddy/backup)                   | —                               | pending |
| 8 | GitHub Actions (ci.yml + deploy.yml)                      | —                               | pending |
| 9 | Runbook stubs                                             | —                               | pending |

Each slice was developed in its own worktree, committed atomically, pushed, then fast-forward merged to `main`. Branches are preserved for traceability. Worktrees are still checked out on disk — safe to leave or prune (`git worktree remove <path>`).

## How the backend works now

Everything below is on `main` and covered by tests (`cd backend && go test ./...`).

- **Binary:** `backend/cmd/entlib/` with three files — `main.go` (dispatcher), `serve.go`, `seed.go`.
- **Env vars:**
  - `ENTLIB_DB_DSN` — SQLite path (default `./entlib.sqlite`).
  - `ENTLIB_HTTP_ADDR` — listen addr (default `:8080`).
  - `ENTLIB_COOKIE_SECURE` — `true`/`false`, default `true`. Set to `false` for local HTTP dev or the Secure-cookie attribute blocks the session cookie.
- **DB:** `modernc.org/sqlite` (pure-Go, no cgo) with the pragmas from ADR 0002. Schema is at version 3 (`user`, `user_credential`, `session`, `title`, `library_entry`, `rating`), applied via `pressly/goose` migrations embedded in the binary at `backend/db/migrations/`.
- **Seeding:** `entlib seed --display-name Kira --username kira --password 'at-least-fourteen-chars'`. The password floor is 14 per ADR 0003 (decided 2026-04-18). Re-running rotates password + display name in place. **Username is the `user.id`** (since `id` is opaque per spec — the seed command uses the username as the id). This is the one piece ADR 0003 doesn't spell out explicitly; don't change it without thinking through the login lookup path.
- **Auth handlers:**
  - `POST /login` — form `username` + `password`. Constant-time path: unknown usernames run a dummy argon2 verify so timing doesn't distinguish "no such user" from "wrong password." Sets `ENTLIB_SESSION` (HttpOnly, Secure, Lax).
  - `POST /logout` — deletes the session row and clears the cookie.
  - `GET /api/me` — returns `{user_id, display_name}` for authenticated requests, 401 otherwise. Touches the session (sliding window; skips updates within 24h).
  - `GET /api/health` — returns `{"status":"ok"}`. No auth required.
- **CSRF:** Double-submit cookie per ADR 0005. Middleware wraps the entire mux. Mutating methods (POST/PUT/PATCH/DELETE) need `X-CSRF-Token: <value>` matching the `ENTLIB_CSRF` cookie (non-HttpOnly so the SPA can read it). CSRF fires *before* routing, so POSTs without a valid token return 403 not 405.

### End-to-end smoke that confirmed slice 5

```
ENTLIB_DB_DSN=/tmp/x.sqlite ./entlib seed --display-name Kira --username kira --password correcthorsebatterystaple
ENTLIB_DB_DSN=/tmp/x.sqlite ENTLIB_COOKIE_SECURE=false ./entlib &

# Bootstrap CSRF cookie
curl -c cookies.txt http://127.0.0.1:8080/api/health
CSRF=$(awk '/ENTLIB_CSRF/ {print $7}' cookies.txt)

# Login
curl -b cookies.txt -c cookies.txt -H "X-CSRF-Token: $CSRF" -d 'username=kira&password=correcthorsebatterystaple' http://127.0.0.1:8080/login

# Whoami
curl -b cookies.txt http://127.0.0.1:8080/api/me
# {"display_name":"Kira","user_id":"kira"}
```

### Non-obvious bits you may trip on

- **User ID == username.** Not documented in ADR 0003; inferred from the seed command's `--username` + the `user.id` opaque-string rule. If you want to change this (e.g., add a separate `username` column on `user_credential`), it's a V4 migration plus a login-lookup change.
- **The `dummy hash` at package init** in `backend/internal/httpx/auth.go` runs `auth.HashPassword` at process start (~200 ms). It only runs once per process, and it's what equalises timing for unknown-username logins. Don't remove it.
- **Tests use fast argon2 params** via `hashWithParams` with tiny mem/time costs. The production round-trip test (`TestHashPassword_DefaultParamsRoundTrip`) uses real params and takes ~200 ms — it's the only slow password test.
- **SQLite `user` table is quoted in SQL** (`"user"`). It's a SQL reserved word in Postgres (fine in SQLite), and the schema matches ADR 0003's example. Keep the quotes for grep-ability and Postgres portability.
- **CSRF check happens before mux routing.** A POST to a GET-only path returns 403, not 405. There's a dedicated test for this (`TestRouter_POSTWithoutCSRFReturns403`) so don't let anyone "fix" the 403-vs-405 mismatch without reading it.

## What to do first, in order

### 1. Slice 6 — Frontend skeleton + embed.FS wiring

**Goal:** a Vite/React/TS SPA under `frontend/`, embedded into the Go binary via `//go:embed`, with just enough UI to prove the auth pipeline through the browser.

Per ADR 0005 the stack is: React (latest stable), TypeScript strict, Vite, Tailwind, React Router, TanStack Query, Zustand, Vitest + React Testing Library, Lucide icons. Don't re-litigate any of those.

Concrete shopping list for the slice:

1. `frontend/` scaffold via Vite's `react-ts` template. Strip the demo stuff.
2. Tailwind + PostCSS config wired up.
3. React Router with three routes: `/login`, `/`, `/*` → redirect based on auth state.
4. A thin `useAuth` hook backed by TanStack Query against `GET /api/me`.
5. Login page that reads the CSRF cookie with `document.cookie`, POSTs to `/login` with `X-CSRF-Token` header, then invalidates the `/api/me` query on success.
6. Hello-world library view (auth-gated): "Hello, {display_name}" + logout button.
7. `backend/internal/web/assets.go` — `//go:embed frontend/dist/*` and an `http.Handler` that serves them (with the caching headers from ADR 0005 — `index.html` no-cache, hashed `/assets/*` immutable).
8. Non-/api, non-asset paths fall through to `index.html` so deep links work.
9. `air` or similar for backend hot-reload during dev; Vite's proxy points to the Go backend on `:8080` for `/api`, `/login`, `/logout`.

**Watch out for:** the `embed.FS` wants a directory that exists at `go build` time. CI needs to run `pnpm build` *before* `go build`. Locally you'll want a `make dev` target or equivalent that runs both (`vite dev` on `:5173` + `air` on the Go side).

### 2. Slice 7 — Deploy artifacts

- `deploy/systemd/entlib.service` with `Restart=always`, `User=entlib`, `EnvironmentFile=/etc/entlib/env`, `WorkingDirectory=/var/lib/entlib`.
- `deploy/caddy/Caddyfile` — single vhost terminating TLS, proxying `127.0.0.1:8080`.
- `deploy/backup/nightly-backup.sh` — `sqlite3 .backup` + `age` + `scp` to Hetzner Storage Box (decided 2026-04-18).

### 3. Slice 8 — GitHub Actions

- `.github/workflows/ci.yml` — on PR/push: `go test`, `go vet`, `pnpm -C frontend install --frozen-lockfile && pnpm -C frontend build && pnpm -C frontend test`, then `go build`.
- `.github/workflows/deploy.yml` — on push to `main`: same build, then scp the binary + `systemctl restart entlib`. Gate behind a `workflow_dispatch` trigger at first.

### 4. Slice 9 — Runbook stubs

- `docs/runbooks/phase-0-provisioning.md` — VPS setup order (apt, user, dirs, first binary, env file, seed command).
- `docs/runbooks/restore-from-backup.md` — stop service, `age -d`, `sqlite3 .restore`, start service.
- `docs/runbooks/seed-users.md` — `entlib seed` usage, the ≥14-char password rule, rotation procedure.

## What is **not** the agent's job

- **Provisioning the VPS, registering the domain, creating the Hetzner Storage Box, getting the TMDB API key.** All of those are user-hands-on steps. Stop at the boundary and print a checklist.
- **Rewriting already-merged ADRs.** They can be superseded with new ADRs, not edited in place.

## Housekeeping the next session could do

- The four Spring Boot worktrees (`auth-baseline`, `auth-google-oidc`, `auth-polish`, `backend-skeleton`) are vestigial from a previous architecture and unrelated to current code. They are safe to delete (`git worktree remove` + `git branch -D`). Left alone so far because they were not in scope.
- The per-slice worktrees (`go-backend-skeleton`, `slice-2-sqlite-migrations`, …, `slice-5-auth`) are fine to prune if you want a tidier `git worktree list`; the branches can stay for traceability.

## Conventions to respect (unchanged from last handoff)

- `~/.claude/CLAUDE.md`: loose TDD, use worktrees, atomic commits with short-but-expressive messages, push without asking, excessive happy/unhappy/edge tests, prettier on the frontend.
- `specs/` is prose — don't put JSON Schema / OpenAPI / test fixtures there.
- Every ADR Follow-up must also appear in `OPEN-QUESTIONS.md`. Resolved items move with a pointer; they don't disappear.

## Things the agent got wrong (updated)

- Tried to FF-merge a branch into itself from the branch's worktree — silently no-op'd. Fix: run `git merge --ff-only <branch>` from the `main` worktree, not the slice worktree.
- Forgot `go mod tidy` prunes requires for packages nothing imports yet; `go get` alone isn't enough if no source file references the package.
- Attempted to re-Write `main.go` without Read-ing it first — got "File has not been read yet" from the tooling; used Read then Write. Be ready for that pattern when overwriting existing files.
- (Still true from last time) Don't shared-cache in-memory SQLite for tests. Use `t.TempDir()` per test.
