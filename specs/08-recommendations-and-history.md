# Recommendations and History

## Why this exists

Once the couple has been using the app for a few months, two things become valuable that weren't possible on day one: **personal recommendations grounded in their own taste** (not generic popularity), and a **history view** that makes their year of watching feel like something they can look back on. This spec covers both, and also serves as the catch-all for tangents that didn't warrant their own file: re-watch handling, yearly wrap-ups, and search.

## Personal recommendations

Separate from Discover (which is global), personal recommendations live in their own tab and on the homepage's "Tonight's pick."

### Signals used

- User's highly-rated titles by Overall Score (mean of the four dimensions, per ADR 0006)
- Genres and tones the user has rated well
- Directors/creators appearing in multiple 4+ rated titles
- Runtime patterns (does this user finish 3-hour movies or bail?)
- Implicit rewatch signal: titles the user has watched two or more times (per `05-watch-tracking.md`)
- Negative signals: abandonment reasons, low Vibe scores on specific styles
- Partner overlap: shared ratings where both users rated highly

### What the recommendations tab shows

- **Because you loved X** — 3–5 rails, each anchored to a specific highly-rated title from the user's own history. Example: *"Because you loved Past Lives"* → other quiet, character-driven dramas.
- **You two agree on** — titles where Kira and M both rated 4+, with suggestions of similar titles.
- **For a quiet night / For a fun night / For a heavy night** — mood-based rails derived from the Vibe and Entertainment dimensions of past ratings.
- **Blind spot** — one genre the user has watched very little in, with a well-reviewed entry point.

Recommendations never surface titles already watched or already on a watchlist, unless the user explicitly toggles "include rewatches."

## Rewatches

- A rewatch is a second (or third, etc.) watch event on a previously-watched title.
- Logging a rewatch is explicit: the app never silently overwrites a prior watch.
- Each rewatch can have its own rating. The history shows progression.
- A "Due for a rewatch?" section surfaces highly-rated titles (Overall Score 4+) last watched 2+ years ago.

## History

A dedicated tab, organized by time.

### Scenario: "what did we watch in March?"

Kira taps History → March 2026. She sees a chronological list of every title she marked watched that month, grouped by week. Each entry shows her rating and M's rating side by side (where M has rated). A small totals row at the top: *"12 movies, 3 seasons, 47 episodes — 38 hours."*

### Scenario: end-of-year wrap-up

On January 1, the app surfaces a wrap-up card: Kira's year in watching. Includes:

- Total watched (with hours)
- Top 10 by overall rating
- Most-watched genre
- Biggest disagreement with M
- A "hidden gem" — a title she rated highly that has a User Score below 6.5
- Longest binge (most episodes of one series in a week)

The wrap-up is savable as an image for sharing, but not auto-shared anywhere.

## Search

- Global search across all titles (via the metadata providers), plus local scope filters: *In my library, On a watchlist, Watched, Unwatched.*
- Search honors the shared-space context — searching from within the "Kira & M" space includes M's library in the scope filters.
- Search by person is supported: "everything with Florence Pugh," "directed by Lynne Ramsay." These are structured queries, not free-text.

## Activity feed (shared space only)

A collapsible panel on each shared-space dashboard shows recent activity:

- "M added *The Substance* to Movie Night — 2 days ago"
- "Kira moved *Dune: Part Two* to #1 — yesterday"
- "M rated *Challengers* 4.2 — 3 hours ago"

The feed is informational, not interactive. It's a way to glance at what the other person has been doing without needing a notification.

## Settings the user will want

- Which streaming services you subscribe to
- Region (affects availability and release windows)
- Content preferences: hide content ratings above X
- Notification preferences (in-app indicators only in v0; see `00-overview.md`)
- Rating dimensions you care about (the four defaults — Story, Performance, Vibe, Entertainment — can be hidden per-user; the Overall Score remains visible)
- Shared-space members, invite link, leave space

## Help, feedback, and support

- **Help center** linked from Settings; searchable articles for common flows (sharing, importing, deleting).
- **In-app feedback** form (Settings → Send feedback): short message, optional screenshot, auto-attached device and app version. Submissions route to the product team.
- **Bug report** CTA is a one-tap affordance on every error state.
- Support email response target: 2 business days.

## Accessibility of recommendations and history

- The wrap-up card is also available as plain text (not image-only) for screen readers.
- Rails and filter chips on the recommendations tab are keyboard-navigable.
- Stats charts include tabular data fallbacks.

## Deliberately out of scope

- ML-heavy personalization requiring training. The recommender is a ranked-blend of simple signals, tuned for legibility ("we suggested this because…") over black-box accuracy.
- Social features beyond the shared space.
- Integrations that post to or sync with Letterboxd, Trakt, etc.
