# entertainment-library

A shared library for two people to remember what they've watched together and decide what to watch next.

Not a streaming service. Not a social network. Not a global catalog. One shared list, either person can edit.

## Status

**Specs + architecture decisions, no code yet.** The product shape is fixed (see `specs/`); the first architectural decision (hosting & runtime) is in `adr/0001`.

## Repository layout

```
.
├── README.md            ← you are here
├── OPEN-QUESTIONS.md    ← decisions deferred, grouped by source
├── LICENSE              ← MIT
├── .gitignore
├── specs/               ← prose product scenarios
│   ├── 00-overview.md   ← start here
│   ├── 01-titles.md
│   ├── 02-library.md
│   ├── 03-ratings.md
│   └── 04-data-model.md
└── adr/                 ← architecture decision records
    ├── 0001-v0-hosting-and-runtime.md
    ├── 0002-v0-datastore-sqlite.md
    └── 0003-v0-auth-password-per-user.md
```

## How to read this repo

Start with `specs/00-overview.md`. The four following spec files cover titles, the shared library, the rating shape, and the data model. Each spec ends with a **"Deliberately out of scope"** section — if something isn't there, it isn't in the MVP.

Read ADRs in numeric order to understand *why* each technical decision was made.

## Conventions

- Specs are written in prose, not schemas or code.
- Scope is kept deliberately small. If a feature isn't necessary to answer *"what have we watched and what should we watch next?"*, it is out.
- ADRs follow: Status, Context, Decision, Rationale, Alternatives, Consequences, Sources.
