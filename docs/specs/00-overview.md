# Overview

A shared library for two people to remember what they've watched together and decide what to watch next.

Not a streaming service, not a social network, not a global catalog. **One shared list**, mutable by either person.

## Core concepts

- **Title** — a movie or a series pulled from TMDB.
- **Library entry** — the couple's relationship to a title: *want*, *watching*, *watched*, or *abandoned*.
- **Rating** — a 0–5 score from one person, with an optional one-line note.

## Primary users

Two fixed accounts, seeded at deploy. No signup, no invites, no roles. Either user can edit any library entry.

## Scope

- Web app, desktop and mobile browser.
- English only.
- One shared library (not per-user).
- Manual status changes; no integration with any streaming service.

## What the spec files cover

- `01-titles.md` — where title data comes from and what gets displayed
- `02-library.md` — the one shared library and its statuses
- `03-ratings.md` — the rating shape
- `04-data-model.md` — entities and relationships

## Deliberately out of scope (for the MVP)

Accessibility audits, internationalization, themes, notifications, discovery rails, homepage recommendations, episode-level tracking, activity feed, multi-dimension ratings, shared-space invites, real-time collaboration, undo, ordering. Each may return in a later revision, but none is necessary to answer "what have we watched and what should we watch next."
