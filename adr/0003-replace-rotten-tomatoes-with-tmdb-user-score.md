# ADR 0003: Replace Rotten Tomatoes with the TMDB User Score

- **Status:** Accepted
- **Date:** 2026-04-15
- **Deciders:** Kira
- **Supersedes:** —
- **Superseded by:** —
- **Related:** ADR 0002, `specs/02-content-metadata.md`, `specs/03-discovery-browsing.md`

## Context

The original product spec (`specs/02-content-metadata.md`) made the Rotten Tomatoes Tomatometer and Audience Score the most prominent rating signal on every title card and detail page. ADR 0002 then surfaced an inconvenient fact: **there is no public Rotten Tomatoes API**. RT data is licensed via Fandango partnerships at terms incompatible with a small two-person product, and scraping is fragile and against RT's ToS.

We need a single, reliable, free, commercially-usable rating source that is already a byproduct of the metadata calls we are making to TMDB (per ADR 0002).

## Decision

**Replace all Rotten Tomatoes scores in the product with TMDB's User Score (`vote_average`), shown as a single number on a 0–10 scale alongside the vote count.**

Concretely:

- The card and detail page show: `User Score 7.8 · 12,431 votes` (or the equivalent formatted string), where 7.8 = `vote_average` and 12,431 = `vote_count` from TMDB.
- A title with **fewer than 100 votes** displays `Not enough ratings yet` instead of a score, to avoid noise from sparse data.
- The score is labeled **"User Score"**, not "Rating," to make clear it reflects audience opinion, not critical consensus.
- TMDB attribution remains, per their terms (`specs/02-content-metadata.md`).
- Sorting and filtering surfaces (e.g., "Top Rated" rails in `specs/03-discovery-browsing.md`) use the same `vote_average` value, with a minimum-vote-count threshold (default 100) to keep low-sample outliers out of the rankings.

## Rationale

- **Most accessible.** TMDB is already our metadata provider (ADR 0002). The User Score arrives in the same API response — zero additional integration, zero additional latency, zero additional cost.
- **Cheap.** $0 marginal cost. Falls entirely under TMDB's commercial license whenever we activate it.
- **Representative.** TMDB has a large global voting base. Popular titles routinely have tens of thousands of votes; even mid-tier titles have hundreds. The signal is noisy at low N (mitigated by the 100-vote threshold) but solid at scale.
- **Single number is honest.** RT's two-score format (Tomatometer + Audience) often confuses users — they conflict, and people don't know which to trust. A single user score is more legible and reflects what the spec is actually doing: helping a couple decide what to watch tonight.

## Alternatives considered

- **IMDb rating via OMDb.** Would have given us the most-recognized rating brand. Rejected because OMDb's CC BY-NC 4.0 license forbids commercial use, and we want the codebase MIT-licensed (per overall repo direction). Re-using OMDb data in a freely-distributable MIT codebase creates a license conflict for any downstream commercial use.
- **IMDb rating via the official AWS Data Exchange product.** Same disqualifier as in ADR 0002: starts at $150,000/year. Off the table.
- **Letterboxd average rating.** No public API; scraping is against ToS. Same problem as RT.
- **Metacritic score.** No public API.
- **Aggregating multiple sources into a meta-score.** Over-engineering for a product whose only goal is to help two people pick a movie. Adds latency, complexity, and explanation burden.

## Consequences

### Positive

- One number per title, one source of truth, one column in the data model.
- No additional vendor relationship beyond TMDB.
- The minimum-vote threshold gives us a clean, defensible reason for showing "Not enough ratings yet" — better UX than a confidently-displayed `9.5 · 4 votes`.
- Removes the "Rotten Tomatoes hole" flagged in the repo review as the largest unresolved spec dependency.

### Negative

- **Loss of the critic-vs-audience distinction.** RT's Tomatometer captured critic consensus; TMDB User Score does not. For some titles (e.g., a film critics loved but audiences didn't), the user-only score will tell a one-sided story. Acceptable for a couch-decision tool; would matter for a critics' product, which this is not.
- **TMDB's voting base skews toward people who use TMDB / its tracking apps.** Slightly different demographic than IMDb or Letterboxd. Not a defect in practice — still tens of thousands of votes on anything popular.
- **Brand recognition.** "Rotten Tomatoes" carries weight users instantly understand. "User Score" requires one second of pattern-matching. Mitigated by the explicit label and vote count.

### Follow-ups

- Update `specs/02-content-metadata.md`, `specs/03-discovery-browsing.md`, and any examples that mention RT.
- Add `voteAverage` and `voteCount` to the `Title` entity in `specs/09-data-model.md`.
- Decide the **vote-count threshold** for inclusion in "Top Rated" rails. Starting value: 100. Re-evaluate after first month of use.
- If a future ADR ever revisits this decision (e.g., a partnership becomes possible), the data model already supports it: just add a new field rather than overwriting `voteAverage`.

## Sources

- [TMDB API — Movie Details (vote_average, vote_count)](https://developer.themoviedb.org/reference/movie-details)
- [TMDB API for Business](https://www.themoviedb.org/api-for-business)
- ADR 0002 — Source Movie and Series Metadata from a Public/Open Database
