# Titles

## Source

All title metadata comes from [TMDB](https://www.themoviedb.org). No other providers, no cross-reference IDs, no Wikidata.

A *title* is either a **movie** or a **series**. Seasons and episodes are not modeled; the MVP treats a series as a single atomic unit.

## Lookup

The user types into a search box. The app calls TMDB's `/search/multi` endpoint and renders the top results as a grid of posters with year and title underneath.

Picking a result either:

- creates a new local `Title` row by copying the TMDB metadata needed (see below), or
- returns the existing local row if the TMDB ID already matches.

In either case the user is taken to that title's page, where they can add it to the library or rate it.

## Fields stored and displayed

Only the minimum needed to render a recognizable entry:

- Poster URL
- Title (TMDB English name)
- Release year
- Kind (`movie` or `series`)
- Synopsis (TMDB `overview`)

Nothing else. No credits, no genres, no streaming availability, no content warnings, no certifications, no runtime.

## Freshness

Title metadata is fetched **once on add** and not refreshed. If TMDB updates a poster or synopsis later, the app does not notice. Acceptable for a library that is read more often than it is resynced.

## Deliberately out of scope

- Multiple language metadata (English only)
- Cross-provider IDs (IMDB, TVDB, Wikidata)
- Automatic metadata refresh
- Credits, cast, crew
- Streaming availability
- Content warnings and certifications
- Seasons and episodes as first-class entities
