# ADR 0005: Real-Time Collaboration via Polling on List/App Open

- **Status:** Accepted
- **Date:** 2026-04-15
- **Deciders:** Kira
- **Supersedes:** —
- **Superseded by:** —
- **Related:** ADR 0004, ADR 0007, `specs/04-watchlists.md`, `specs/06-homepage-next-up.md`

## Context

Two parts of the product are collaborative and benefit from one user seeing the other's recent changes:

- **Shared watchlists** — Kira drags a title up, M needs to see the new order on her device.
- **Activity attribution / homepage** — "added by M" tags, "M finished S2 of *Severance*" indicators.

The original spec used hand-wavy language like "near-real-time" and "M is active now." A real real-time stack (WebSockets, SSE, AppSync) is operational overhead we don't want to take on for a two-person product, and SQLite + Spring Boot on a single VPS (ADR 0007) doesn't lend itself to long-lived connections at scale. The user has chosen the simplest possible approach: **polling**.

## Decision

**Use polling on relevant client surfaces. No WebSockets, no SSE, no push.**

Concretely:

- **Watchlist views (the main collaborative surface):** when a list view is open, the client polls its endpoint every **8 seconds**. The poll is a `GET /api/watchlists/{id}` request that returns the list with an ETag/`Last-Modified` header — the server returns `304 Not Modified` when nothing has changed. Polling stops when the tab is hidden (`document.visibilityState === "hidden"`) and resumes on focus.
- **App open / route change:** every full page navigation revalidates the relevant queries via TanStack Query (ADR 0004). This means opening the app or moving between sections always shows fresh data.
- **Manual refresh:** a pull-to-refresh gesture on mobile (Tailwind + a small touch hook) and a refresh icon on desktop trigger an immediate re-fetch.
- **Optimistic UI:** local actions (dragging an entry, marking watched, rating) update the UI immediately and reconcile against the next poll. Conflicts on reorder use last-write-wins per entry, per `specs/04-watchlists.md`.
- **No server push.** No notifications, no live presence indicator (the "M is active now" element from `specs/06-homepage-next-up.md` is dropped or replaced by a manual "couch mode" toggle).

The polling interval (8s) is configurable via a single backend constant so we can dial it up or down without redeploying the client.

## Rationale

- **Simplest thing that works.** A two-person product polling every 8 seconds while a list is open generates ~7-15 requests per minute total across the household. Trivial cost on the SQLite + Spring Boot stack.
- **Fits the stack.** Spring Boot + SQLite on a single VPS handles short HTTP requests with `304 Not Modified` extremely well. WebSockets would require a different connection model and complicate the deploy story.
- **Fits the product.** Collaboration in this app is two people on a couch deciding what to watch, not 50 people editing a Figma board. Eight seconds of latency on a list reorder is invisible at human pace.
- **Already supported by chosen libraries.** TanStack Query has `refetchInterval` and `refetchOnWindowFocus` built in. Zero additional libraries.
- **Cheap to revisit.** If polling ever feels too laggy, swapping in SSE for the watchlist view is a localized change behind the same query hook.

## Alternatives considered

- **WebSockets (e.g., Spring's `@MessageMapping` / STOMP).** Real real-time, but adds a connection model that doesn't compose with `304 Not Modified` and HTTP caching, and requires its own auth handshake. Overkill for two users.
- **Server-Sent Events (SSE).** Lighter than WebSockets but still a long-lived connection per open list view. Worth a future revisit if polling proves insufficient.
- **AWS AppSync / managed real-time.** Off the table — ADR 0007 supersedes the AWS direction in v0.
- **Manual refresh only (no polling).** Cheapest, but degrades the felt collaboration: M wouldn't see Kira's reorder until she explicitly refreshed. Rejected.
- **Higher polling frequency (e.g., 2s).** Tested mentally: would still be cheap on this stack but adds noise without changing the UX (a person dragging takes >2s anyway). 8s is the chosen sweet spot.

## Consequences

### Positive

- No long-lived connections to manage.
- Works through any HTTP-friendly proxy, browser, or network condition.
- The 304-response shape means most polls cost almost nothing on the backend.
- Pauses cleanly when the tab is hidden — no battery drain on backgrounded mobile browsers.

### Negative

- **Up-to-8-second delay** on collaborative changes. Acceptable per the rationale above; explicitly stated in `specs/04-watchlists.md`.
- **No live presence.** "M is active now" disappears as a feature. The "With M tonight?" homepage section becomes a manual *Couch mode* toggle instead of an automatic detection.
- **Wasted requests when nothing is changing.** Mitigated by ETag/`If-None-Match` so the body isn't sent. The cost in request overhead is real but tiny.

### Follow-ups

- Update `specs/06-homepage-next-up.md` to drop automatic presence detection in favor of a manual "Couch mode" toggle.
- Update `specs/04-watchlists.md` to state the explicit 8-second polling interval and ETag behavior.
- Add a setting (Settings → Activity) to disable polling for users who prefer manual refresh only — useful on metered connections.
- Revisit if a third user joins regularly and felt latency becomes a complaint.

## Sources

- [TanStack Query — refetchInterval](https://tanstack.com/query/latest/docs/framework/react/guides/query-options)
- [MDN — HTTP conditional requests (ETag, If-None-Match)](https://developer.mozilla.org/en-US/docs/Web/HTTP/Conditional_requests)
