# Content Metadata

## Why this exists

Every title in the app — whether it's sitting on a watchlist, in a discovery feed, or in someone's watch history — needs to look good and carry enough context to decide in under ten seconds whether to watch it tonight. That means a poster, a rating you trust, and just enough detail (runtime, year, who's in it, what it's about) to make the call.

## Where the data comes from

The app pulls from upstream sources rather than maintaining its own catalog. Conceptually:

- **TMDB** for everything title-facing: poster art, backdrops, release year, genre, runtime, cast, synopsis, episode lists, streaming availability (via TMDB's JustWatch integration), and the **User Score** (`vote_average` 0–10 with `vote_count`) shown in place of an external critic rating. See ADR 0002 (provider choice) and ADR 0003 (User Score replaces Rotten Tomatoes).
- **Wikidata** as a secondary, open (CC0) source for stable cross-reference IDs (TMDB ID, IMDb ID, TVDB ID, Wikidata QID), per ADR 0002. Used for migration insurance, not for display.

The exact providers can change; what matters is that each title in the app has a stable internal ID and the external IDs needed to refresh its metadata nightly. Display should still work if one source is unavailable — if a User Score is missing or based on too few votes, show "Not enough ratings yet" rather than a confidently-displayed `9.5 · 4 votes`.

## What gets displayed on a title card

The standard title card, seen everywhere (search results, watchlists, discovery), shows:

- Poster (portrait, sharp, cached)
- Title
- Year (or year range for series, e.g., "2022–")
- **User Score** (TMDB `vote_average`, e.g., `7.8 · 12k votes`); titles with fewer than 100 votes show `Not enough ratings yet` instead
- Runtime (movies) or "S3 · 28 ep" shorthand (series)
- Top genre tags (max 2, e.g., "Thriller · Sci-Fi")
- A small "where to watch" row of streaming-service logos

### Example card (prose render)

> **Poor Things** *(2023)*
> User Score 7.7 · 12k votes · 2h 21m · Comedy · Drama
> Streaming on: Hulu, Max
> [poster image]

## What gets displayed on a title detail page

Tapping a card opens detail. This is the page you read before deciding whether to add to a list. It includes:

- Large poster + backdrop
- Synopsis (2–4 sentences, truncated with "more")
- Director (movies) / creator (series)
- Main cast (top 5)
- TMDB User Score with vote count (e.g., `7.8 / 10 · 12,431 votes`)
- Runtime, release date, content rating (PG-13, TV-MA, etc.)
- For series: season list with episode counts and air-date ranges
- Streaming availability, with a direct deep link if possible
- **Your status**: "Not in library," "On watchlist: Movie Night," "Watched 2024-06-02"
- **Partner's status**: "M watched this in March, rated 4/5"
- Action buttons: *Add to watchlist*, *Mark as watched*, *Rate*

## Series-specific metadata

Series need structure movies don't:

- Ordered list of seasons, each with an episode list.
- Each episode has a title, number, air date, runtime, and synopsis.
- Episodes can be flagged as "premiere," "finale," or "special," used for pacing hints ("you're about to start the final season").

### Scenario: viewing *The Bear* detail

Kira opens *The Bear*'s detail page. She sees three seasons listed. Season 3 is expanded because it's the most recent. Next to each episode is a small check if she's watched it, and a faded check if M has watched it but Kira hasn't. At the bottom, "Next up for you: S3E5 — *Children*."

## Content warnings and parental guidance

Each title carries the standard content rating (PG-13, R, TV-MA, etc.) and, where upstream data provides them, content-warning tags: violence, sexual content, strong language, drug use, self-harm, flashing imagery.

These are surfaced:

- As small chips on the detail page (below the synopsis).
- As an avoidance filter in Discover ("hide titles with: flashing imagery").
- As a visible warning on the watch-mark sheet when a title carries a warning the user has flagged as sensitive.

The default is to show everything; the app does not moralize. Users opt into filtering.

## Localization of metadata

- Titles, synopses, and episode names are shown in the user's selected language where a localized version exists upstream. English is the fallback.
- Release dates follow the user's region (e.g., a UK user sees the UK theatrical date).
- Runtime and date formatting follow the user's locale (see `00-overview.md`).

## Availability data

- Streaming availability is region-specific. A US user and a UK user see different service logos on the same title.
- "Rent / buy" options are shown below streaming options on the detail page but do not drive discovery ranking.
- Availability refreshes at least daily. When data is stale beyond 48 hours, the availability row shows a "last checked" hint rather than silently lying.

## Image and asset handling

- Posters and backdrops are served from a CDN in multiple resolutions. The client requests the right one for the layout, so list scrolls don't fetch oversized images.
- A placeholder poster (title typeset on a neutral background) renders when artwork is missing or fails to load, rather than a broken-image icon.
- Images are cached locally with an LRU cap (~200 MB on mobile, configurable) so frequent titles stay offline-available.

## Freshness and correctness

- Metadata refreshes nightly in the background for titles in any user's library.
- A "Report wrong info" affordance on the detail page lets the user flag incorrect episode counts, wrong poster, etc. The flag doesn't edit data directly; it queues for review.
- Posters and images are cached locally so scrolling a watchlist stays smooth offline.

## Deliberately out of scope

- User-uploaded posters or fan art.
- Editing synopses.
- Tracking books, games, or podcasts. This is a movies-and-series app.
