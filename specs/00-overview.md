# Overview

## What this product is

A small, personal entertainment library for a couple (and eventually a handful of close friends) to decide what to watch together, keep track of what they've already seen, and rate it afterward with enough structure to actually mean something later.

It is **not** a public social network, not a streaming service, and not a catalog of everything ever made. It is a shared decision-making and memory tool that sits next to Netflix/Max/Prime/etc. and answers three questions well:

1. **What's worth watching right now?** (discovery)
2. **What have we already agreed to watch together?** (watchlists)
3. **What did we think of it after?** (ratings & history)

## Primary users (initial)

- **Kira** — the primary account holder. Uses the app on desktop and phone.
- **Kira's girlfriend** — second account, shares watchlists with Kira. Equal editing rights on shared lists.

The system should assume two-person collaboration as the default case, but nothing in the model should break if a third or fourth trusted person is added later (e.g., a friend joining for a specific show).

## Core concepts

- **Title** — a movie or a series. Series contain seasons, which contain episodes. Movies are atomic.
- **Library entry** — the couple's relationship to a title: "want to watch," "watching," "watched," "abandoned."
- **Watchlist** — an ordered collection of titles curated by one or more users. A user has personal watchlists and shared watchlists.
- **Watch event** — the act of marking something watched. For a movie, this is one event. For a series, it is per-episode, and seasons/series roll up from episode state.
- **Rating** — a structured opinion recorded after a watch event, tied to the user who gave it (not the couple).

## Tone and feel

Posters forward. Information-dense but uncluttered. Feels like a well-organized shelf, not a spreadsheet. The couple should enjoy opening the app on a Saturday evening to decide what to watch, not treat it as a chore.

## Platforms

**v0 ships as a single web app**, mobile-responsive from ~360 px phone widths up through desktop. There are no native iOS or Android apps in v0 — the couple-only scope doesn't justify the engineering cost (see ADR 0004 for the frontend stack and ADR 0007 for the backend). Features that would require native (e.g., browser-blocked push notifications, deep OS integration) are out of scope for v0.

Native mobile apps may follow in v1 if the product opens beyond the couple-only phase.

## Accessibility commitment

The app targets **WCAG 2.1 AA** as a baseline. That means, at minimum:

- All interactive elements reachable by keyboard (desktop) and by VoiceOver/TalkBack (mobile).
- Color contrast of at least 4.5:1 for body text, 3:1 for large text and icons.
- Every poster image has a descriptive alt label derived from the title ("Poster for *Poor Things* (2023)").
- RT scores and ratings are never conveyed by color alone — the number is always present.
- Focus states are visible, not suppressed.
- Motion (list reordering animations, scene transitions) respects the OS "reduce motion" preference.

Accessibility is treated as correctness, not a feature. A regression here is a bug.

## Internationalization

**v0 ships in German (de-DE) and English (en-US) at parity** — both languages are first-class from day one. The design accommodates additional locales without rework:

- All strings are externalized; no hard-coded copy in components. Frontend uses react-i18next; backend uses Spring `MessageSource`.
- Dates, runtimes, and release years follow the user's locale (e.g., "2h 11m" in en-US, "2 Std. 11 Min." in de-DE).
- Region setting affects content availability (streaming services, release windows) and discovery rankings.
- Title metadata is pulled in the user's language from TMDB where available, with English as fallback. Posters prefer the localized version where one exists.

Additional locales (French, Spanish, Portuguese, etc.) are deliberately out of scope for v0.

## Theme and visual system

- **Light, Dark, and Auto** themes. Auto follows the OS setting.
- Dark is the expected default for evening viewing — the most common usage time.
- Posters never get filtered or tinted by the theme. Artwork stays true to the source.

## Notifications

v0 is web-only and uses **in-app indicators only** — no browser push, no email digests, no APNs/FCM:

- **In-app badges**: count of new activity since last open (new shared-list additions, partner finished something you're also watching), clearable on view.
- **Activity feed** in each shared space (per `08-recommendations-and-history.md`).
- Surfaces refresh on app/list open via polling (per ADR 0005).

Browser push, email digests, quiet hours, and per-event toggles are deferred until v1 introduces native apps.

## Error, loading, and empty states

Every screen has three explicit designs the product team reviews:

- **Loading** — skeleton placeholders that match the final layout (never a spinner on a blank page for more than 300ms).
- **Empty** — an explanation plus a primary CTA. Never just "no results."
- **Error** — a friendly message plus a retry affordance. Technical errors are logged; users see plain language.

Offline is its own case: cached content renders; writes queue and sync on reconnect with a small banner indicating the queued state.

## Privacy and legal

- A public **Terms of Service** and **Privacy Policy** are linked from signup, Settings, and the footer. Users accept both at account creation.
- Users can **delete their account** from Settings. Personal data is purged immediately. Shared-list contributions remain, re-attributed to "former member."

## Support

- **Help center** with searchable articles, linked from Settings.
- **In-app feedback** form: short message + optional screenshot, routes to the team.
- **Bug report** affordance attaches device/app version automatically.
- SLA: support responses within two business days during the early-access phase.

## What the spec files cover

- `01-accounts-and-sharing.md` — users, pairing, the shared-library model
- `02-content-metadata.md` — where title data comes from, what gets displayed
- `03-discovery-browsing.md` — most watched / top rated with date ranges
- `04-watchlists.md` — personal lists, shared lists, collaborative ordering
- `05-watch-tracking.md` — marking episodes, seasons, and movies as watched
- `06-homepage-next-up.md` — the "what should I watch next" surface
- `07-ratings-and-reviews.md` — structured post-watch rating criteria
- `08-recommendations-and-history.md` — inferred tangents: suggestions, re-watches, stats
- `09-data-model.md` — entities, relationships, and derived states referenced by all of the above
