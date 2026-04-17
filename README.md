# entertainment-library

A shared library for two people to remember what they've watched together and decide what to watch next.

Not a streaming service. Not a social network. Not a global catalog. One shared list, either person can edit.

## Status

**Specs only.** This repo contains the MVP product specification and nothing else. No code, no infrastructure decisions, no architectural choices — those are deliberately deferred until the product shape is stable.

## Repository layout

```
.
├── README.md            ← you are here
├── LICENSE              ← MIT
├── .gitignore
└── specs/               ← prose product scenarios
    ├── 00-overview.md   ← start here
    ├── 01-titles.md
    ├── 02-library.md
    ├── 03-ratings.md
    └── 04-data-model.md
```

## How to read this repo

Start with `specs/00-overview.md`. The four following files cover titles, the shared library and its statuses, the rating shape, and the data model. Each spec ends with a **"Deliberately out of scope"** section — if something isn't in the specs, it isn't in the MVP.

## Conventions

- Specs are written in prose, not schemas or code.
- Scope is kept deliberately small. If a feature isn't necessary to answer *"what have we watched and what should we watch next?"*, it is out.
