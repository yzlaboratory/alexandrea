# Ratings and Reviews

## Why this exists

A single 1–5 star rating doesn't do justice to how the couple actually talks about what they watched. *Oppenheimer* was a 5 for craft and a 3 for entertainment. *Bridgerton* is a 2 for story and a 4 for "comfort viewing on a Sunday." Kira wants a structured rating system that captures these dimensions so that a year later, scrolling through history, "oh right — we loved the vibe but the plot was thin" is immediately readable.

Ratings are personal, not shared. Kira and M each rate independently after watching. Seeing your partner's rating is part of the fun.

## When ratings are prompted

The rating flow fires after a watch event:

- **After a movie:** single rating prompt, can be dismissed with "Rate later."
- **After an episode:** no prompt. Episodes aren't rated individually.
- **After a season finale:** a season-level rating prompt.
- **After a series finale (or marking a series "finished"):** a series-level rating prompt.
- **After abandoning a series:** a prompt asking *why* (see criteria below).

The flow is never blocking. Users can skip and come back from the title detail page later.

## Rating dimensions (criteria)

A completed rating consists of 1–5 stars (in 0.5 steps) on each of the following four dimensions, plus an optional short note (per ADR 0006):

| Dimension | What it captures | Example |
| --- | --- | --- |
| **Story** | Plot, pacing, writing | *Succession*: 5 |
| **Performance** | Acting, chemistry, presence | *Past Lives*: 5 |
| **Vibe** | Atmosphere, cinematography, sound | *Mad Max: Fury Road*: 5 |
| **Entertainment** | Did I have a good time? | *John Wick 4*: 5 |

The user can skip any dimension. Minimum viable rating is any one dimension filled in. The app computes the **Overall Score** as the arithmetic mean of the filled dimensions and displays it as the headline number on the title detail page, in history, and in stats. The individual dimensions remain available on hover/tap so the Overall Score is never the whole story.

### Why these four

- **Story** and **Performance** cover the traditional critical axes.
- **Vibe** captures the modern "did it feel good to sit inside" sense that's often why a show becomes a favorite despite a thin plot.
- **Entertainment** separates "objectively good" from "I enjoyed it."
- A previously-considered "Would rewatch" dimension was dropped (ADR 0006) — it overlapped with Entertainment in practice, and rewatch behavior is now measured implicitly via repeat `WatchEvent` rows (`specs/05-watch-tracking.md`).

## The rating UI

A bottom sheet with four sliders (or four rows of five-star pickers), each labeled, each skippable. A short single-line note field at the bottom. Two buttons: *Save rating* and *Skip for now*. Default state is empty — no pre-filled 3 stars, no anchoring.

### Scenario: Kira rates Poor Things

Kira taps "Rate now" after marking *Poor Things* watched. She drags:

- Story: 4
- Performance: 5
- Vibe: 5
- Entertainment: 4

Note: *"Visually unreal. Emma Stone owned it."* Saves. Overall lands at **4.5** (mean of 4, 5, 5, 4).

M, on her own device, rates the same movie — Story 3, Performance 5, Vibe 5, Entertainment 3. Note *"Weird in a way I couldn't fully settle into."* Overall **4.0**.

On the title detail page for *Poor Things*, Kira now sees both columns side by side, each in their avatar color. The detail page's "Your rating" row shows Kira's 4.5, and below it, "M's rating: 4.0."

## Abandonment reasons

When a user abandons a series, they're shown a quick reason picker:

- "Pacing too slow"
- "Didn't click with the characters"
- "Too heavy right now"
- "Plot lost me"
- "Just ran out of interest"
- Custom note

Abandonment reasons feed the recommendation engine (don't push similar pacing / similar tone).

## History and editing

- Every rating is editable at any time from the title's detail page.
- A rating has a first-rated date and a last-edited date.
- Rewatches create a second rating entry — the user can rate the same movie differently on a second viewing. The history page shows the progression ("watched 2024-06-02: 3.8 → rewatched 2025-10-11: 4.4").

## Stats and rollups (per user)

- **Top of the year** — highest-rated titles watched in a calendar year.
- **Disagreement board** — titles where Kira and M's overall ratings differ by 1.5+, ranked by magnitude. Surfaces the fun arguments.
- **Criterion breakdown over time** — e.g., "you tend to rate Vibe 0.5 higher than Story on average." Not a judgment, just a mirror.
- **Genre affinity** — average rating per genre, shown as bars.

## Spoiler handling in notes

- The single-line note field has an optional **"contains spoilers"** toggle. When set, the note renders blurred on any surface visible to users who haven't marked the title watched (including the partner). Tap to reveal.
- When a partner's note contains a spoiler and the reader hasn't watched, the reveal requires a deliberate confirmation: *"This note may spoil the ending. Show anyway?"*

## Privacy of individual ratings within a shared space

- By default, ratings are visible to shared-space members (seeing each other's scores is half the fun).
- Each user can set a per-rating or global **"keep private"** toggle that hides their score from the partner while still counting it for their own stats and recommendations. Default: off.
- A private rating is indicated in the user's own view so they're never unclear about what the partner sees.

## Rating history and audit

- Every rating edit stores the previous value, timestamp, and editor (always the rating's owner — no one else can edit someone's rating).
- The user can view their own rating's edit history from the title detail page. The partner sees only the current rating.

## Accessibility

- Star pickers and sliders have keyboard and screen-reader equivalents: arrow keys change value, voice labels announce "Story, 4 of 5 stars."
- The rating sheet can be submitted with a keyboard shortcut (`cmd/ctrl + enter`) on desktop.

## Loading and error states

- Submitting a rating optimistically renders; if the save fails, a toast offers *Retry* and the rating remains editable in the sheet without data loss.
- Opening the rating sheet for a title with no prior rating shows empty dimensions — never pre-filled 3-stars.

## Deliberately out of scope

- Long-form written reviews. The note field is short (single line, ~280 chars) by design — this isn't Letterboxd.
- Public sharing of ratings outside the shared space.
- Forced rating before closing a completed series. Users who don't want to rate shouldn't be nagged; a dismissable prompt is plenty.
