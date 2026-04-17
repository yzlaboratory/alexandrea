# ADR 0005: v0 Frontend — React SPA Bundled into the Go Binary

- **Status:** Accepted
- **Date:** 2026-04-17
- **Deciders:** Kira
- **Supersedes:** —
- **Superseded by:** —
- **Related:** ADR 0001, ADR 0002, ADR 0003, ADR 0004

## Context

ADR 0001 deferred the frontend shape. Two candidates were on the table: server-rendered HTML (htmx + Go `html/template`) and a single-page app (React/TypeScript) talking JSON to a Go backend.

MVP interactions are small but want to feel snappy: changing a library entry's status, submitting a rating, search-as-you-type against TMDB. Those are the cases where SPA sugar shows up on screen; everything else either shape would handle equally well.

The deploy constraint from ADR 0001 and ADR 0002 is "one Go binary." Anything we pick needs to preserve that.

## Decision

Ship a **React + TypeScript SPA**, built by **Vite**, **embedded into the Go binary** and served by the Go server.

Concretely:

- **Frontend stack:**
  - React (latest stable), function components + hooks only.
  - TypeScript in strict mode; no `.js` files in `src/`.
  - Vite as build tool and dev server.
  - Tailwind CSS for styling (mobile-first breakpoints).
  - React Router for client-side routing.
  - TanStack Query for server state (caching, retries, invalidation after mutations, optimistic updates).
  - Zustand for the tiny slice of client-only state (modal open/closed, form drafts before submit). If we never actually need it, we drop it.
  - Vitest + React Testing Library for unit/component tests when any exist.

- **Build & serve:**
  - `pnpm build` in `frontend/` emits `frontend/dist/`.
  - The Go build copies `dist/` into an `embed.FS` and serves it at `/`.
  - `index.html` is served with `Cache-Control: no-cache`; hashed Vite assets under `/assets/` with `Cache-Control: public, max-age=31536000, immutable`.
  - A deployment is still one artifact: `entlib` binary. No separate static host, no CDN.

- **Dev loop:**
  - `vite dev` on `:5173` with a proxy to the Go backend on `:8080` for `/api`, `/login`, `/logout`.
  - Go reloads via `air` or equivalent when source changes.

- **HTTP backend shape:**
  - Go stdlib `net/http` (1.22+ pattern-based routing). No router library in v0.
  - JSON REST under `/api/*` for everything the SPA reads or writes.
  - Auth endpoints (`/login`, `/logout`) are form-encoded POSTs that set/clear the session cookie from ADR 0003.
  - `/api/csrf` returns a CSRF token on first load (described below).

- **CSRF:**
  - Double-submit cookie pattern. A non-`HttpOnly` cookie `ENTLIB_CSRF` is set on first GET; the SPA reads it with `document.cookie` and echoes its value in an `X-CSRF-Token` header on every mutating request. A ~30-line Go middleware rejects mismatches. The session cookie from ADR 0003 stays `HttpOnly`.

- **State of play between pages:** the SPA is fully client-routed. The Go server catches any non-`/api` path that isn't a static asset and returns `index.html` so deep links work.

## Rationale

- **SPA pays off where the MVP shows it.** Status changes and rating submits feel nicer with optimistic updates; search-as-you-type against our proxied TMDB endpoint is much cleaner in JS than htmx swaps. htmx would still work, but we'd be fighting it on exactly the interactions we care about.
- **TanStack Query removes hand-rolled cache/retry/invalidate code.** At four endpoints we'd write that code anyway — might as well use the library everyone else uses.
- **`embed.FS` keeps the single-binary property.** No nginx, no S3 + CloudFront, no `/var/www/` to sync. `scp entlib user@host:` is still the whole deploy.
- **Stdlib `net/http` is enough.** Pattern routing (`"GET /api/titles/{id}"`) lands in 1.22. `chi` / `echo` buy us ergonomics worth ~one afternoon and one dependency. For this scope, skip.
- **Double-submit CSRF** is the standard pattern for cookie-auth SPAs. No session-side token storage, just a cookie and a header to compare.

## Alternatives considered

- **htmx + `html/template` (server-rendered).** Genuinely tempting for scope. Rejected because the interactions we care about are client-friendly, and the Go side stays simpler when it only speaks JSON to one frontend shape.
- **Next.js.** SSR and SSG we do not need; running a Node process in production conflicts with ADR 0001's deploy shape. Rejected.
- **SvelteKit / Svelte.** Smaller bundle, pleasant ergonomics. Rejected because React is the skill already in the room.
- **`chi` or `echo` HTTP router.** Nicer middleware chain, better ergonomics. Rejected — not worth the dep at this scope.
- **JWT-based auth to simplify SPA fetch.** Rejected in ADR 0003 in favor of server-side sessions; the SPA simply sends cookies with `credentials: "same-origin"`.
- **Service Worker / PWA shell.** Deferred. Not worth the complexity for two users on the open internet in v0.
- **No `Zustand`, just TanStack Query + React state.** Honestly plausible. We include Zustand because it's featherweight and keeps the door open.

## Consequences

### Positive
- Deploy stays one binary (ADR 0001 preserved).
- Optimistic updates, snappy search, and client-side routing all arrive cheaply via the React ecosystem.
- API shape is JSON-first, which makes later scripts / integrations / CLI trivial.
- CSRF is one middleware applied uniformly.

### Negative
- **Bundle cost.** ~60–100 KB gzipped for the deps before product code. Acceptable behind a login wall.
- **Cold first paint is slower** than htmx: HTML → JS bundle → hydrate → render. For two users with warm caches, invisible.
- **Node at build time.** The Go binary is still singular, but whoever builds it needs Node + pnpm locally (or in CI). Not a runtime concern on the VPS.
- **Two languages to keep in sync.** TypeScript types for the JSON API aren't auto-generated from the Go structs in v0; drift risk is real if we forget. A `go run ./cmd/schema-gen` or similar is an easy follow-up if it bites.

### Follow-ups
- Decide how Node arrives at deploy time (CI runs `pnpm build`, or local build + commit the dist — **CI** is the obvious default once the deploy workflow lands).
- Decide TypeScript type generation from the Go API (hand-written vs. generated). Deferred until the drift actually hurts.
- Decide hot-reloader for Go during dev (`air` vs. `reflex` vs. raw `go run`). Not ADR-worthy.
- Pick an icon set when needed (Lucide is a fine default).
- Decide PWA / Service Worker strategy if offline support ever becomes a goal.

## Sources

- [Vite](https://vitejs.dev/)
- [TanStack Query](https://tanstack.com/query/latest)
- [Go `net/http` routing in 1.22](https://go.dev/blog/routing-enhancements)
- [Go `embed` package](https://pkg.go.dev/embed)
- [OWASP — CSRF double-submit cookie pattern](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#double-submit-cookie)
