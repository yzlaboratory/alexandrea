# entertainment-library

A small, shared decision-making and memory tool for a couple (and eventually a handful of close friends) to:

1. **Browse** what's worth watching now (most watched, top rated, by date range).
2. **Curate** collaborative watchlists with ordering they tug on together.
3. **Track** what they've watched, episode by episode and movie by movie.
4. **Rate** what they watched with structured dimensions, not a single 5-star reductor.
5. **Decide** what to watch tonight from a homepage tuned to that question.

This is **not** a streaming service, not a public social network, and not a global catalog. It sits next to Netflix/Max/Prime/etc. and answers "what now?" well.

## Status

**Planning / pre-implementation.** This repo currently contains the product spec and architecture decisions; no code yet.

## Repository layout

```
.
├── README.md            ← you are here
├── LICENSE              ← MIT
├── .gitignore
├── specs/               ← prose product scenarios with named examples
│   ├── 00-overview.md   ← start here
│   ├── 01-accounts-and-sharing.md
│   ├── 02-content-metadata.md
│   ├── 03-discovery-browsing.md
│   ├── 04-watchlists.md
│   ├── 05-watch-tracking.md
│   ├── 06-homepage-next-up.md
│   ├── 07-ratings-and-reviews.md
│   ├── 08-recommendations-and-history.md
│   └── 09-data-model.md ← entities and relationships
└── adr/                 ← architecture decision records
    ├── 0000-template.md
    ├── 0001-use-aws-as-infrastructure.md          (superseded by 0007)
    ├── 0002-use-public-metadata-database.md
    ├── 0003-replace-rotten-tomatoes-with-tmdb-user-score.md
    ├── 0004-frontend-stack-react-zustand.md
    ├── 0005-real-time-collaboration-via-polling.md
    ├── 0006-rating-dimensions-trimmed-to-four.md
    └── 0007-v0-backend-spring-boot-sqlite-on-vps.md
```

## v0 stack at a glance

- **Frontend:** React + TypeScript + Vite + Zustand + TanStack Query, Tailwind CSS, react-i18next (DE/EN). Web-only, mobile-responsive. (ADR 0004)
- **Backend:** Spring Boot 3 (Java 21) + SQLite (WAL) + Flyway, on a single Hetzner VPS behind Caddy. (ADR 0007)
- **Metadata:** TMDB primary, Wikidata for cross-reference IDs. TMDB User Score replaces Rotten Tomatoes. (ADR 0002, ADR 0003)
- **Collaboration:** Polling on list/app open, ~8s interval with ETag. No WebSockets. (ADR 0005)
- **Scope:** Two users (Kira + partner), web-only, German + English, ~€6/month all-in.

## How to read this repo

- New here? **`specs/00-overview.md`** explains the product, the users, and the core concepts. It also indexes the rest of the spec files.
- Want to know *why* a technical decision was made? Read the ADRs in numeric order.
- Looking for the data shape? **`specs/09-data-model.md`** has entities and relationships.

## Conventions

- **Specs** are written in prose with named example users (Kira, M) and named example titles (*Poor Things*, *Severance*, *The Bear*). They describe scenarios, not screens.
- Every spec ends with a **"Deliberately out of scope"** section. If something isn't in a spec, the spec is the place to add it — not implicitly via code.
- **ADRs** follow the format in `adr/0000-template.md`. Status, Context, Decision, Rationale, Alternatives, Consequences, Sources.
- Open questions and unresolved decisions are flagged as "Follow-ups" in the relevant ADR rather than left as TODO comments.
