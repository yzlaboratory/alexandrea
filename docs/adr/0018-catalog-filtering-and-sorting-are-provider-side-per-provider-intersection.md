# Catalog filtering and sorting are delegated provider-side; the surface exposes the per-provider intersection

All catalog browse filtering and sorting is **delegated to the upstream
provider** — we never fetch a page and filter or reorder it client-side.
The one consequence we accept for this is that a filter or sort is offered
**only for the media types whose provider can honor it natively**; the
browse surface therefore exposes the *intersection* of what TMDB,
OpenLibrary, and IGDB each support, conditioned on `media_type`.

## Why provider-side only

The catalog is live and paginated 20-at-a-time into an infinite-scroll grid
(ADR 0001). Client-side filtering would fetch 20 rows, locally discard most
of them, and render ragged chunks — pagination counts and "load more" stop
meaning anything. Delegating to the provider keeps each fetched page a full
page of matching results. The cost is that providers disagree on what they
can filter and sort by, so the offered controls become asymmetric per type.
This is the same per-type asymmetry already accepted in ADR 0004 (strict
per-media-type surfaces) and ADR 0006 (asymmetric external-rating coverage),
so it will not surprise.

## Best-fit endpoint per state

Each provider exposes the browse surface through more than one endpoint, and
the surface switches to the best fit for the current state:

| State | TMDB | OpenLibrary | IGDB |
|---|---|---|---|
| nothing applied | `/movie/popular`, `/tv/popular` | `/trending/daily.json` | `/games` sorted `total_rating_count desc` |
| filter/sort applied | `/discover/movie`, `/discover/tv` | `/search.json` (`sort=`, field filters) | `/games` with `where`/`sort` |
| text search active | `/search/movie`, `/search/tv` | `/search.json` (`q=`) | `/games` `search "…"` |

## Verified capability map

Sorts — all four are available for **all four media types** when no text
search is active:

| Sort | TMDB | OpenLibrary | IGDB |
|---|---|---|---|
| popularity ↓ | `sort_by=popularity.desc` | `sort=trending` | `sort total_rating_count desc` |
| release date ↓ | `sort_by=primary_release_date.desc` | `sort=new` | `sort first_release_date desc` |
| title ↑ | `sort_by=original_title.asc` | `sort=title` (`title_sort asc`) | `sort name asc` |
| external rating ↓ | `sort_by=vote_average.desc` | `sort=rating` (`ratings_sortable desc`) | `sort total_rating desc` |

External-rating sort excludes null-rated entries per ADR 0006.

Filters — availability is per type:

| Filter | TMDB (Movies/TV) | OpenLibrary (Books) | IGDB (Games) |
|---|---|---|---|
| genre | `with_genres` (native enum) | `subject:(alias OR alias …)` per ADR 0013 | `where genres = (…)` (native enum) |
| original language | `with_original_language` ✅ | ❌ no original-language concept | ❌ no original-language concept |
| available-in language | ❌ no `/discover` param | `language:<marc3>` ✅ | `where language_supports.language = (…)` ✅ |
| runtime | `with_runtime.gte/.lte` ✅ | n/a | n/a |
| page count | n/a | `number_of_pages_median:[min TO max]` ✅ | n/a |

OpenLibrary specifics were verified against the live search docs, the
`WorkSearchScheme.sorts` source mapping, the documented searchable-field
list, **and live calls to `/search.json`**. Two findings from the live calls
matter: (a) `number_of_pages_median` *is* a queryable Solr range field
(`number_of_pages_median:[300 TO 400]` returns only 300–400-page books,
HTTP 200) even though it is absent from the documented searchable-field list
— so page-count filtering for Books is in; (b) `/search.json` returns
intermittent HTTP 500s on `sort=` and range-query shapes (the documented
`first_publish_year:[…]` example 500'd repeatedly while sibling queries
succeeded), so the OpenLibrary adapter must retry with backoff rather than
treat a single 500 as "unsupported" or "empty".

## Casualties (deferred, see #3 out-of-scope)

Two filter × media-type combinations have no provider support and are
deferred rather than faked client-side:

1. **Available-in-language for Movies/TV** — no TMDB `/discover` parameter.
2. **Original-language for Books/Games** — no provider models it.

## Behavior under active text search

TMDB `/search/*` accepts only `query`/`year`/`language` (no genre, sort,
runtime, or original-language); IGDB `search` cannot be combined with an
explicit `sort`; OpenLibrary `/search.json` *can* combine `q=` with `sort=`
and field filters. So when a text search is active, any sort or filter the
provider's search endpoint cannot honor is **disabled with a "not available
while searching" note**, and re-enabled when the search clears.

## Consequences

- The set of filter and sort controls shown is a function of `media_type`;
  the UI must drive controls from a per-type capability table, not a fixed list.
- Adding a new provider means re-deriving the intersection, not assuming parity.
- No client-side filtering/sorting code path exists to "patch" a missing
  provider capability — the deliberate absence is what keeps infinite scroll honest.
