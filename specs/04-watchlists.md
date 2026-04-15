# Watchlists

## Why this exists

A watchlist is the commitment layer: "we've decided we want to watch this, eventually." The couple needs a small number of named lists they can reorder collaboratively, not one infinite bucket. Ordering matters — the top of the list is what you watch next, so two people tugging on that order is the whole interaction model.

## Types of lists

- **Personal watchlist** — one per user by default, named "My List." Invisible to others. The user can create additional personal lists (e.g., "Solo horror," "Comfort rewatches").
- **Shared watchlist** — lives in a shared space, editable by all members. The couple's default shared list is "Movie Night." They can create more (e.g., "Series Together," "Rainy Sunday").

A title can live on multiple lists simultaneously. Adding *Challengers* to "Movie Night" doesn't remove it from Kira's personal "Solo watch someday."

## The anatomy of a list

A list has:

- A name and an optional emoji or color.
- An ordered sequence of **entries**. Each entry is a title plus metadata: who added it, when, and an optional note ("her pick," "for a rainy day").
- A member list (for shared lists only).
- A computed header: total runtime, count of movies vs. series, count of already-streamable-by-us items.

Entries are ordered by explicit drag-and-drop rank, not by date added. New entries default to the bottom, but the user is prompted: "Add to top or bottom?" if the list has more than 5 items.

## Collaborative ordering

This is the feature that carries the product. Two people need to nudge the same list toward "what we'll actually watch next" without a fight.

### Scenario: M adds a pick, Kira re-ranks

M adds *The Substance* to "Movie Night." It drops in at the bottom (position 8). Kira opens the app that evening, sees the new title with an "added by M" tag, reads the synopsis, likes it, and drags it up to position 2. The list now shows:

1. *Dune: Part Two* (added by Kira, 4 days ago)
2. *The Substance* (added by M, moved up by Kira — small icon indicating recent activity)
3. *Poor Things*
4. ...

On M's device, the list updates in near-real-time if the app is open; otherwise on next open. Kira's reorder action shows up in the list's activity log (collapsible, at the bottom of the list) as "Kira moved *The Substance* to #2."

### Scenario: concurrent reordering

Kira drags *Poor Things* from #3 to #1. At the same moment, M drags *Dune: Part Two* from #1 to #5 on her device. The system resolves this with last-write-wins per-entry position, and the loser sees a subtle "M moved *Dune* after you" confirmation. There is no blocking merge dialog. If a true conflict keeps flipping, the app shows a "list is changing rapidly — tap to refresh" banner rather than silently fighting.

### Scenario: a veto

M wants to watch *Saltburn*. Kira doesn't. Instead of removing it from the shared list (which feels confrontational), Kira taps the title and marks it *Not for me* — this is a soft-veto. The title moves to a "One of us passed" section at the bottom of the list, visible but deprioritized. Either person can un-veto. If both veto, it's auto-archived from the list.

## Actions on a list entry

- **Move up / move down** (drag, or long-press → position)
- **Open detail** (tap)
- **Add a note** ("date night!", "her mom recommended")
- **Mark watched** — starts the watch-tracking flow (see `05-watch-tracking.md`)
- **Remove from list**
- **Veto** (shared lists only)

## Smart list behaviors

- **"Watched" auto-removal.** When a title is marked watched by all members of the shared space, it leaves the shared list automatically and moves to the shared history. If only one person watched it, the title stays, with an "M watched ahead" badge.
- **Availability decay.** If a title leaves all of the couple's subscribed streaming services, it's flagged with a "leaving soon" or "not streaming" indicator but not auto-removed.
- **Reorder by availability.** A "Leaving soon" sort option pulls titles that are about to drop off Netflix/Max/etc. to the top temporarily, as a decision aid.

## Creating and managing lists

- Creating a list: tap "New list," name it, pick emoji, choose personal or shared.
- A list can be converted from personal to shared (promotes all current entries, attribution stays with the creator).
- Archiving a list hides it but keeps the data. Deleting is a two-step confirm and is rare.

## Bulk actions

A list-view toolbar appears when the user enters **select mode** (long-press on mobile, checkbox toggle on desktop). Once in select mode:

- Tap multiple entries to toggle selection (with a visible count).
- Apply a batch action: *Move to top, Move to bottom, Move to another list, Mark watched, Remove.*
- Escape or "Done" exits select mode without side effects.

This is essential for tidying: the couple occasionally realizes 6 titles on "Movie Night" are already watched elsewhere and want to clear them in one sweep, not one tap each.

## Sort views

The canonical order of a list is the drag-and-drop rank the users set. For reference, users can temporarily **view** a list sorted by:

- Rank (default)
- Recently added
- RT Tomatometer
- Runtime (shortest first — useful when picking a weeknight watch)
- Leaving soon (availability-based)

Switching sort view does **not** change the canonical order. A small pill at the top indicates "Viewing by: Runtime — back to Rank."

## Duplicate detection

When adding a title already on the list, the app says *"This is already on Movie Night at position 4. Want to move it to the top?"* rather than silently adding a duplicate or silently dropping the action.

Adding a title that's already on a different list is allowed — it's a feature, not an error — but the add confirmation surfaces where else the title lives.

## Undo and destructive confirmations

- **Remove from list** is a single tap with a 10-second undo toast. Accidental removes happen constantly; a confirm dialog would be worse than undo.
- **Delete a list entirely** requires typing the list name into a confirmation prompt. This only applies to lists with 5+ entries; small lists are recoverable from the archive.
- **Archive a list** is one tap, always reversible from Settings → Archived Lists.

## Loading, empty, and error states

- **Empty list:** "This list is empty. Add titles from Discover or search." with two buttons to those surfaces.
- **Loading list:** skeleton rows matching the canonical card height.
- **Shared-list sync error:** a banner "Couldn't sync with M's changes — retry?" with a manual refresh button. The list still renders from the last-known state.

## Import

Users can bootstrap their library by importing from:

- **Letterboxd** (CSV export of watched films, watchlist, ratings).
- **Trakt** (API-linked or JSON export).
- **IMDb** (CSV export of lists and ratings).

The import flow previews what will be added, lets the user pick which list the incoming titles land on, and maps ratings to the closest dimension (usually Story + Entertainment). Unrecognized titles are reported with a retry-by-search affordance rather than silently dropped.

## Reminders (opt-in)

A user can pin a note to a list entry with an optional reminder ("remind me Friday"). Reminders fire as a local notification and are personal — M's reminders don't notify Kira.

## Deliberately out of scope

- Public sharing of lists to non-members via URL.
- Templating lists ("start from the AFI Top 100"). A user can manually add, but there's no catalog of pre-made lists.
- Comments threads on individual entries. Notes are single-line and belong to whoever added them.
