# ADR 0004: Frontend Stack — React + Zustand (SPA, Web-Only, Mobile-Responsive)

- **Status:** Accepted
- **Date:** 2026-04-15
- **Deciders:** Kira
- **Supersedes:** —
- **Superseded by:** —
- **Related:** ADR 0007, `specs/00-overview.md`

## Context

The v0 product is **web-only and mobile-responsive** (decided in the scope cut — see `specs/00-overview.md`). No native iOS or Android app is in scope until the couple-only phase ends. The frontend talks to a Spring Boot REST API (ADR 0007) over HTTPS; there's no SSR requirement, no SEO requirement, no marketing site to render server-side.

What the frontend has to do:

- Render lists, grids, posters, and detail pages quickly on phones, tablets, and desktops.
- Hold modest client state: which list is open, drag-in-progress for reordering, modals (rate, mark watched), filter state on Discover.
- Talk to a small REST API for everything else — title metadata, user library, ratings, watch events.
- Localize between **German and English** (per `specs/00-overview.md`).
- Poll the backend on list-open for collaborative reorders (per ADR 0005).

## Decision

**Build the frontend as a single-page application using React + TypeScript + Vite, with Zustand for client state and TanStack Query for server state.**

Concretely:

- **React** (latest stable, function components + hooks throughout).
- **TypeScript** in strict mode; no JS files in `src/`.
- **Vite** as build tool and dev server. No Next.js — we don't need SSR/SSG.
- **Zustand** for client state (UI state, drag state, ephemeral selections).
- **TanStack Query (React Query)** for server state (caching, polling, retries, optimistic updates on watch-marks and ratings).
- **React Router** for client-side routing.
- **react-i18next** for the German/English localization layer; locale files under `src/locales/{de,en}/`.
- **Tailwind CSS** for styling, with a small set of custom design tokens for the poster-forward look. Mobile-first breakpoints.
- **Vitest** + **React Testing Library** for unit and component tests; **Playwright** for the handful of end-to-end smoke tests.

The build output is a static bundle (HTML + JS + CSS) served either by the Spring Boot backend's static resources or by a reverse proxy (Caddy) on the same VPS. See ADR 0007 for the deployment shape.

## Rationale

- **React + Vite** is the fastest path from zero to a working SPA in 2026. The ecosystem is enormous, hiring is easy if it ever matters, and the user is comfortable with React.
- **Zustand** is the smallest workable state library: one store hook, no providers, no boilerplate. Redux would be massively overkill at this scope; raw Context would push too much state into trees.
- **TanStack Query** owns the server-state layer cleanly, so client state and server state never get tangled. Polling for collaborative list updates (ADR 0005) becomes a one-line `refetchInterval` on the relevant query.
- **No SSR.** This is a private web app behind login. SEO and first-paint over slow 3G are not constraints. Every dependency that exists to solve those problems can be skipped.
- **Tailwind** keeps the styling decisions in component files (no global CSS sprawl) and makes the responsive breakpoints obvious.

## Alternatives considered

- **Next.js (React).** Stronger framework, but ships SSR and a routing model we don't need. Build complexity goes up; deploy story changes (we want a static SPA on a single VPS, not a Node server). Rejected.
- **Vue 3.** Equally capable. Rejected because the user picked React.
- **Svelte / SvelteKit.** Smaller bundle, nicer ergonomics in some ways. Rejected because React skill is what's in the room.
- **HTMX + Spring Boot Thymeleaf templates.** Genuinely tempting for a couple-only app — half the moving parts disappear. Rejected because the drag-and-drop list reorder UX (the central interaction in `specs/04-watchlists.md`) is awkward without a real client framework.
- **Redux / Redux Toolkit.** Industry standard but ten times the boilerplate Zustand needs at this scope. Rejected.
- **Recoil / Jotai.** Comparable to Zustand. Rejected because Zustand was named.

## Consequences

### Positive

- Tiny build (Vite + React + Zustand + Query is ~50 KB gzipped before our own code).
- Fast dev loop (Vite HMR).
- Single static-asset deploy story; no Node runtime to manage in production.
- Mobile responsiveness via Tailwind breakpoints, no separate codebase.

### Negative

- **No native app, no app-store presence.** Acceptable in v0 (couple-only, web-only). Means push notifications are off the table — the spec already drops them.
- **Drag-and-drop on touch screens is finicky.** Mobile drag interactions in browsers are weaker than native. Mitigation: use a battle-tested library (e.g., `dnd-kit`) and accept that long-press-then-drag is the touch fallback.
- **SPA bundle ships a lot of JS to do anything.** Acceptable for an authenticated tool; unacceptable would be a marketing site.

### Follow-ups

- Decide on the drag-and-drop library (`dnd-kit` is the current frontrunner).
- Decide on the icon set (Lucide is light and free).
- Decide on the date/locale formatting library (native `Intl` is probably enough for DE/EN).
- Add Storybook only if the component count grows beyond ~25.

## Sources

- [Vite documentation](https://vitejs.dev/)
- [Zustand documentation](https://zustand.docs.pmnd.rs/)
- [TanStack Query](https://tanstack.com/query/latest)
- [react-i18next](https://react.i18next.com/)
- [Tailwind CSS](https://tailwindcss.com/)
- [dnd-kit](https://dndkit.com/)
