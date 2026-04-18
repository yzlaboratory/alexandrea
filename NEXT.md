# NEXT

Handoff note for the next session. Self-contained — readable cold.

## Where the repo is (2026-04-18, end-of-day)

**MVP is feature-complete.** Every user-facing behaviour described in
`specs/00-overview.md` through `specs/04-data-model.md` is implemented,
tested, and green on `main`. The remaining work is *operational* — nobody
can reach it at a real URL yet.

- **5 product specs** in `specs/`, **5 ADRs** in `adr/`, frozen.
- **Backend (Go).** Single binary `entlib` with `serve` and `seed`. Auth
  (argon2id + SQLite sessions + CSRF double-submit), TMDB server-side
  proxy with retry + singleflight, titles CRUD, library CRUD, ratings.
- **Frontend (React/Vite/TS).** Embedded into the binary via `//go:embed`.
  Library view with three tabs, TMDB search + add, title page with
  status controls and two-rater side-by-side rating widgets, average
  score badge on library rows.
- **Deploy artifacts.** `deploy/systemd/entlib.service`,
  `deploy/caddy/Caddyfile`, `deploy/backup/nightly-backup.sh`.
- **CI.** `.github/workflows/ci.yml` green on every slice.
  `.github/workflows/deploy.yml` is still `workflow_dispatch`-only;
  promote to push-to-main after the 3rd clean manual deploy (ADR 0001).
- **Runbooks.** `docs/runbooks/{phase-0-provisioning,restore-from-backup,seed-users}.md`.

### Commit log since the last handoff

```
b1a8de5 Add rating widgets on TitlePage + average on Watched tab
65b2b8d Add POST/DELETE /api/library/:id/rating
945ad41 Add library.Rating + embed ratings in Entry
8160de3 Replace HomePage stub with real library view + search + title page
422c859 Add /api/library CRUD handlers
7b38973 Add library package and POST/GET /api/titles handlers
9c086c7 Wire TMDB client into serve and require TMDB_API_KEY at startup
b037459 Add /api/search and /api/tmdb/title/:kind/:id handlers
47925af Add tmdb client package per ADR 0004
520d2a0 Extract RequireAuth middleware and refactor Me
```

### Slice progress

| #     | Slice                                                | Status |
|-------|------------------------------------------------------|--------|
| 1–5   | Backend skeleton / migrations / auth                 | done — merged |
| 6     | Frontend skeleton + embed.FS wiring                  | done — merged |
| 7     | Deploy artifacts                                     | done — merged |
| 8     | GitHub Actions CI + deploy                           | done — merged |
| 9     | Runbook stubs                                        | done — merged |
| 10    | TMDB server-side proxy                               | done — merged |
| 11    | Title + library CRUD                                 | done — merged |
| 12    | Ratings                                              | done — merged |

## HTTP surface as it stands today

Unauthenticated:
- `GET /api/health`
- `POST /login` — form `username` + `password`, sets `ENTLIB_SESSION`
- `POST /logout`

Authenticated (session cookie + CSRF):
- `GET /api/me`
- `GET /api/search?q=<query>` (TMDB only; 503 if `ENTLIB_TMDB_OPTIONAL`)
- `GET /api/tmdb/title/{kind}/{id}` (`kind` ∈ `movie`/`series`)
- `POST /api/titles` — `{tmdb_id, kind}`; idempotent on `tmdb_id`
- `GET /api/titles/{id}`
- `GET /api/library?status=want,watching` — default filter is `want,watching`
- `POST /api/library` — `{title_id, status?}`; 409 on duplicate
- `PATCH /api/library/{id}` — `{status}`
- `DELETE /api/library/{id}` — 204
- `POST /api/library/{id}/rating` — `{score: 0-5, note?}`; implicitly transitions entry to `watched`
- `DELETE /api/library/{id}/rating` — scoped to caller's own rating

## Non-obvious bits you may trip on

- **User ID == username.** Not documented in ADR 0003; `entlib seed`
  writes the `--username` value into `user.id` directly.
- **CSRF fires before the mux.** A POST to a GET-only path returns 403,
  not 405 — `TestRouter_POSTWithoutCSRFReturns403` enforces this; don't
  let anyone "fix" the 403-vs-405 mismatch without reading the test.
- **`web.FrontendBuilt()` gate.** Any backend test that depends on the
  embedded SPA must branch on this. Fresh checkouts have `dist/.gitkeep`
  only — the `httpx` asset-404 test used to fail until it was gated
  (c984944).
- **`username:kind` collision on TMDB IDs.** The `title.tmdb_id` column is
  UNIQUE — lookup is by `tmdb_id` alone. If a movie and a series ever
  shared an ID in TMDB (they don't; IDs are media-type-scoped), we'd
  reject the second. Edge case, not worth extra schema cost.
- **Ratings trigger implicit status transition.** Rating any non-`watched`
  entry flips it to `watched` atomically in the same request. Don't add
  a separate "rate and mark watched" button on the UI — the single action
  is correct.
- **Library query invalidation is whole-cache, not per-row.** Rating
  mutations can change an entry's `status`, which moves it between tabs —
  a surgical patch is tempting but wrong. Keep the blunt invalidate.

## What to do first, in order

### 1. Provision the VPS

Walk through `docs/runbooks/phase-0-provisioning.md`. Stop points for
the user (not the agent):

- [ ] Register a domain (open item in `OPEN-QUESTIONS.md` — `.de` vs
      `.app` still undecided).
- [ ] Create the Hetzner CX22 in `nbg1` and point A/AAAA records at it.
- [ ] Create a Hetzner Storage Box, note username + host, set up an SSH key.
- [ ] Get a TMDB API key.
- [ ] Generate an `age` keypair and put the **secret key somewhere that
      is not this VPS** (the runbook is explicit: this is the one
      failure mode backups cannot save us from).

Once that's done, the runbook's terminal commands finish the setup.

### 2. First three manual deploys

Per ADR 0001, `deploy.yml` stays `workflow_dispatch`-only until the 3rd
clean manual deploy. After the third, flip the trigger to
`push: branches: [main]` and remove the dispatch-only warning in the
workflow comment.

### 3. Run a restore drill

Decrypt the latest snapshot, integrity-check, diff row counts against
live. The runbook has the recipe. Run it at hand-off and again
quarterly. First drill will probably shake a bug out of the stub
runbook — update it in place.

### 4. (Optional) Address the Node.js 20 deprecation warning

CI emits a warning that `actions/{checkout,setup-go,setup-node,upload-artifact}` and
`pnpm/action-setup` are on Node 20, which GitHub is deprecating
2026-09-16. Set `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24=true` at the
workflow level, or wait for the action authors to ship Node 24–capable
versions. Warning only — nothing breaks until September.

## What is **not** the agent's job

- Provisioning infrastructure (VPS, domain, Storage Box, TMDB API key).
  Stop at the boundary and print a checklist.
- Rewriting already-merged ADRs. They can be superseded; they do not
  get edited in place.
- Declaring the UI "tested" without real browser eyes on it. Unit tests
  and curl verification are necessary, not sufficient — slice 11 and 12
  have *not* been browser-smoke-tested in the sessions that built them.

## Browser smoke test the next session should do

Before anything else, start both dev servers, log in, and walk through:

```
make dev   # prints two commands; run each in its own terminal
```

Then at `http://localhost:5173/`:

1. Log in as the seeded user.
2. Search for a title (requires `TMDB_API_KEY` in the env of the Go
   process — export it before starting).
3. "Add to library" on a result → verify it appears under "On our plate".
4. Click the title → land on `/title/{id}` → click a star → save.
   Verify the status bar flips to Watched and the entry migrates to
   the Watched tab.
5. Remove the rating → verify the library row still exists but loses
   its badge.
6. Remove the entry → gone from the library.

Anything unexpected: file against the slice that introduced it.

## Housekeeping

- The four Spring Boot worktrees (`auth-baseline`, `auth-google-oidc`,
  `auth-polish`, `backend-skeleton`) are still vestigial and safe to
  delete if you want a clean `git worktree list`. They've been left
  alone through every slice because they were never in scope.
- Per-slice branches (`slice-6-frontend` through `slice-12-ratings`)
  remain pushed for traceability; prune if they become noise.

## Conventions to respect (unchanged)

- `~/.claude/CLAUDE.md`: loose TDD, use worktrees, atomic commits with
  short-but-expressive messages, push without asking, excessive
  happy/unhappy/edge tests, prettier on the frontend.
- `specs/` is prose — don't put JSON Schema / OpenAPI / test fixtures there.
- Every ADR Follow-up must also appear in `OPEN-QUESTIONS.md`. Resolved
  items move with a pointer; they don't disappear.
- Test conventions learned across slices:
  - Handler tests call through `RequireAuth(d, handler)` directly for
    mutating paths; go through `NewRouter` only for read-only paths.
    The router's CSRF middleware otherwise short-circuits tests that
    don't bother to forge a matching cookie+header pair.
  - Data-layer tests use `t.TempDir()` per test for the SQLite file —
    never the shared-cache in-memory form.

## Things the agent got wrong (cumulative)

- (Still true) FF-merge a branch into itself from its own worktree
  silently no-ops. Run `git merge --ff-only <branch>` from the `main`
  worktree.
- (Still true) `go mod tidy` prunes requires for unused packages;
  `go get` alone isn't enough if no source file references the dep.
- (Still true) `Write` refuses to overwrite a file that hasn't been
  `Read` first. Read, then Write.
- (Still true) Don't shared-cache in-memory SQLite for tests. `t.TempDir()`.
- (Slice 6) Any test that depends on the embedded SPA must branch on
  `web.FrontendBuilt()`. Fixed in `c984944`.
- (Slice 10) `auth.SessionStore.Load` returns `Session` by **value**,
  not `*Session`. Context attach/retrieve must agree on the form —
  mismatched types silently fail the `.(*auth.Session)` assertion and
  `SessionFromContext` returns `!ok` inside the protected handler.
- (Slice 11) The established test convention is to call mutating
  handlers via `RequireAuth(d, handler).ServeHTTP(...)` and bypass
  the router/CSRF. Trying to route mutating requests through
  `NewRouter` without also forging a CSRF cookie+header will 403
  every time.
- (Slice 12) TypeScript `exactOptionalPropertyTypes: true` (turned on
  by the Vite `react-ts` strict preset) treats `existing?: Rating`
  and `existing: Rating | undefined` as *non*-interchangeable. Prefer
  the explicit `| undefined` form when the value is reassignable.
