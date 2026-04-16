# Discovery and Browsing

## Why this exists

Sometimes the couple doesn't have anything queued up and wants to browse for ideas. The two browsing modes they care about are **what's popular** (most watched) and **what's considered good** (top rated), and both need to be filterable by time so the answer to "what's the best thriller of 2024" is different from "what's the best thriller of all time."

## The two primary axes

- **Most watched** — titles ranked by TMDB's `popularity` signal over the chosen window. (TMDB doesn't expose true global watch counts; popularity is an engagement-weighted proxy. The rail is labeled "Popular" in-product to be honest about this. Answers: "what is everyone paying attention to right now?")
- **Top rated** — titles ranked by **TMDB User Score** (`vote_average`) with a minimum vote-count threshold (default 100) to filter low-N noise, over the chosen window. Answers: "what do other viewers actually think is good?" (See ADR 0003.)

Both axes are available for Movies and for Series separately. The user shouldn't have to see a drama movie and a reality TV show fighting for the same slot.

## Time windows

Every discovery feed can be filtered to:

- **This month** — rolling 30 days
- **This year** — current calendar year to date
- **Last year** — previous calendar year, useful in Jan–Mar when "this year" is still sparse
- **All time** — no date filter

The date filter applies to the *release date* of the title when sorting top rated (a 2024 filter on "top rated" means the best titles *released* in 2024), and to the *trend window* when sorting most watched (a 2024 filter on "most watched" means what had the biggest audience during 2024, regardless of release year).

This distinction matters and should be explained inline. A classic rewatched heavily in 2024 shows up in "most watched 2024" but not in "top rated 2024."

## The discovery page

### Layout

A single scrollable page with rails, each rail being a ranked horizontal row of title cards. Example rails, top to bottom:

1. **Most Watched Movies — This Month**
2. **Most Watched Series — This Month**
3. **Top Rated Movies — This Year**
4. **Top Rated Series — This Year**
5. **Top Rated Movies — All Time**
6. **Top Rated Series — All Time**

Each rail has a "See all" that opens a full ranked list view with the time-window selector at the top (This Month / This Year / Last Year / All Time) and a Movies/Series toggle.

### Scenario: Saturday-evening browse

Kira opens the app, taps Discover. The top rail is "Popular Movies — This Month." She sees *Dune: Part Two* in slot 1, *Challengers* in slot 2. She taps *Challengers*, reads the synopsis, likes the 8.4 User Score (12k votes), and taps *Add to watchlist → Movie Night*. She returns to Discover. The card now shows a small ribbon: "On your list."

### Scenario: "best thriller of 2024"

M wants a thriller. She opens Discover, taps "Top Rated Movies — This Year," taps the genre filter, picks "Thriller." The list re-ranks to show 2024 thrillers by User Score (filtered to titles with at least 100 votes). She sees titles with their User Score, runtime, and streaming logos. She adds two candidates to Movie Night.

## Filters

Inside a "See all" list, filters available:

- **Genre** (multi-select)
- **Streaming service** — "only show stuff I can actually watch on Netflix/Max/Hulu/Prime" (based on the user's declared subscriptions)
- **Minimum User Score** (slider, 0–10) and **minimum vote count** (default 100, configurable)
- **Content rating** (PG-13 and up, etc.)
- **Exclude already-watched / already-on-list** (toggle, on by default)

Filters persist per-session but reset when the user leaves Discover, so opening the app fresh doesn't surprise them with a narrow view.

## Subscriptions model

Each user declares which streaming services they have (Netflix, Max, Hulu, Prime, Disney+, Apple TV+). This powers the "only show stuff I can watch" filter and the streaming-logo rows on cards. A shared space uses the *union* of its members' subscriptions when filtering shared discovery, so the shared "Movie Night" list doesn't exclude a movie just because one partner doesn't have Max.

## Search

A search bar is pinned to the top of Discover and every list view.

- Typing shows an **autocomplete dropdown** with up to 8 matches (titles, people), each with a small poster thumbnail.
- Hitting enter opens a full search results page with tabs: *All, Movies, Series, People*.
- **Recent searches** (last 10) are remembered per-device and shown when the search bar is focused empty. One tap clears them.
- Search is forgiving: typos, partial titles, and alternate titles (e.g., "EEAAO" for *Everything Everywhere All At Once*) resolve.
- Search is keyboard-driven on desktop: `/` focuses it from anywhere; arrow keys navigate results; enter selects; escape dismisses.

## Pagination and load behavior

- "See all" pages use **infinite scroll with a stop at ~200 results**. Past that, the user is prompted to apply filters instead.
- Each batch is 30 items. Loading the next batch shows skeleton cards, not a spinner mid-list.
- A *"Back to top"* floating button appears after the user scrolls past the second batch.

## Loading, empty, and error states

- **Loading:** poster-shaped skeleton rails animate in, preserving layout so nothing jumps when real data arrives.
- **Empty:** "No titles match these filters" with a *Clear filters* button. Never a blank panel.
- **Error:** "Couldn't load this right now" with a *Retry* button. The rest of Discover still renders — one broken rail doesn't take down the page.
- If the upstream data provider is unreachable, Discover falls back to a cached snapshot up to 24 hours old, with a subtle banner indicating staleness.

## Rankings and region

- Rankings are region-specific where the upstream data supports it. A US user's "Most Watched This Month" reflects US audience data.
- A user's region is inferred on first sign-in from the browser's `Accept-Language` header and can be changed any time in Settings — changing it refreshes Discover.

## Deliberately out of scope

- Personalized "because you watched X" rails on the Discover page — that belongs on the homepage (see `06-homepage-next-up.md`).
- Trailers embedded on the Discover page. Trailers live on the detail page.
- Infinite scroll past rank ~200. If the user is looking at rank 200, the filter is wrong, not the list.
