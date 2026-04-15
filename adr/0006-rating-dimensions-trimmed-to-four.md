# ADR 0006: Trim Rating to Four Dimensions, Compute a Single Overall Score

- **Status:** Accepted
- **Date:** 2026-04-15
- **Deciders:** Kira
- **Supersedes:** —
- **Superseded by:** —
- **Related:** `specs/07-ratings-and-reviews.md`, `specs/08-recommendations-and-history.md`, `specs/09-data-model.md`

## Context

The original rating spec (`specs/07-ratings-and-reviews.md`) proposed five dimensions: **Story, Performance, Vibe, Entertainment, Would Rewatch.** The repo review flagged that five sliders is heavy for a post-watch flow and that "Would Rewatch" overlaps significantly with "Entertainment." Industry experience with multi-axis rating systems shows completion rates collapse as the number of required interactions grows.

We want a structured rating system that captures more nuance than a single 5-star score, but not so many fields that the partner ends up skipping ratings entirely (which would starve the recommender and the disagreement board).

## Decision

**Reduce rating dimensions to four (Story, Performance, Vibe, Entertainment) and compute a single Overall Score as the mean of filled dimensions. Drop the "Would Rewatch" dimension entirely.**

Concretely:

- Each rating consists of 0–5 stars (in 0.5 steps) on each of the four dimensions, plus an optional one-line note.
- Any dimension may be skipped. Minimum viable rating is **one filled dimension**.
- **`overallScore` = arithmetic mean of the filled dimensions.** Computed on read; not stored. Displayed prominently on the title detail page and in history.
- The Overall Score is the only number used for sort orders, "Top of the year" rollups, recommendation weighting, and the disagreement board.
- Notes still support the `containsSpoilers` toggle from `specs/07-ratings-and-reviews.md`.

## Rationale

- **Lower friction.** Four sliders is meaningfully shorter than five. The post-watch sheet stays under one screen on a phone.
- **"Would Rewatch" overlapped with Entertainment.** In testing the dimensions mentally against real watches: a movie that was fun to watch and one I'd rewatch are nearly the same call. Heavy films I rated 5 on Story and 1 on Rewatch were already captured by the existing dimensions.
- **Mean-of-filled is honest.** The previous spec already computed an overall as the mean of filled dimensions. Making this *the* number rather than a tiebreaker simplifies every downstream surface (rails, recommendations, stats).
- **Recommender stays informed.** With four dimensions still tracked individually, the recommender can still distinguish "this user values Vibe" from "this user values Story" — we just don't use Rewatch as a signal anymore.

## Alternatives considered

- **Keep all five dimensions.** Rejected — the user explicitly chose to strip Rewatch.
- **Reduce to a single 5-star rating.** Rejected — loses the dimensional nuance that motivated the structured rating in the first place. The whole point was to be more honest than "I gave it 4 stars."
- **Make Overall a weighted mean (e.g., Story counts double).** Rejected — adds a cognitive layer ("why is my Overall 3.7 when I gave three 4s?") and lacks evidence for the weights.
- **Replace Rewatch with a separate "would I rewatch?" yes/no checkbox outside the rating.** Considered, but adds back the friction we just removed. Re-watch behavior can still be measured implicitly: any second `WatchEvent` for the same title is a rewatch (`specs/05-watch-tracking.md`).

## Consequences

### Positive

- Faster post-watch flow; higher expected completion rate on ratings.
- Single number to display in lists, history, and recommendations — less visual noise.
- Cleaner data model: one dimension less, no special-case logic for the rewatch field.
- The "Due for a rewatch?" surface in `specs/08-recommendations-and-history.md` continues to work — it now relies on Overall Score + time since last watch, instead of a dedicated dimension. This is arguably more honest signal anyway.

### Negative

- **Loss of explicit rewatch signal.** A user who rates *Schindler's List* a 5/5 overall but would never rewatch it can no longer say so directly. The signal becomes implicit (they don't, in fact, rewatch it). Acceptable for a couple's tool.
- **Existing per-dimension stats (e.g., "you rate Vibe 0.5 higher than Story") have one fewer axis** — minor and arguably an improvement.

### Follow-ups

- Update `specs/07-ratings-and-reviews.md` (dimensions table, examples, "Why these five" → "Why these four").
- Update `specs/09-data-model.md` to remove the `rewatch` field from `Rating.dimensionScores`.
- Update `specs/08-recommendations-and-history.md` to remove the "weighted toward Would-rewatch" mention in the recommender signals.
- Settings UI no longer shows a per-dimension toggle for Rewatch (one fewer option in the dimensions visibility setting).

## Sources

- `specs/07-ratings-and-reviews.md` (current rating spec, prior to this ADR)
- Repo review notes (April 2026)
