# Detail-overlay URL is a path segment with the external id

The deep-linkable detail overlay defined in #6
uses a **path-segment** URL containing the upstream provider's
**external id**, scoped under the surface route. Examples:

- `/movies/catalog/27205` — TMDB Movie 27205 in Catalog
- `/tv/library/1396` — TMDB TV 1396 in Library
- `/books/watchlist/OL45804W` — OpenLibrary work in Watchlist
- `/games/library/1942` — IGDB game in Library
- `/share/<token>/27205` — entry inside a Share

The surface URL (without the trailing `/<external_id>`) renders the
grid; navigating to the entry URL renders the same grid with the
detail overlay open above it.

## Why path segment, not query parameter

A path segment reads as identity ("this entry, on this surface"); a
query parameter reads as state on a page ("the catalog with a flag
attached"). Every modern detail-overlay product — Letterboxd,
Pinterest, Goodreads — uses path segments for this reason; URLs that
get DMed look intentional. Modern routers (Next.js App Router,
React Router 6.4+, SvelteKit) have first-class nested-route
conventions built around path segments, including "open as overlay
if navigated from the parent grid; open as full page if visited
directly." The path-segment shape is *also* the easier
implementation in any of those stacks.

## Why external id, not the local row id

External id is the only stable identifier per ADR 0001 — we don't
store anything else. Using it in URLs means the URL is durable
across refresh, server restart, cache rebuild, and even data export.
A local row id is per-user (a Library row id is meaningless to a
Friend who doesn't own that row) and would require additional
indirection to share or bookmark.

## No cross-provider collision

Each media type maps to exactly one provider per ADR 0001, and the
URL scopes by media type before the external id. So `/movies/...`
ids only ever come from TMDB and `/games/...` ids only ever come
from IGDB. A numeric collision between providers is structurally
impossible.

## Direct load always opens the overlay, not a full page

Although nested-route routers can render the entry URL as a standalone full
page when visited directly, we do **not** do that. A direct load or refresh of
an entry URL **always** opens the detail overlay over the surface grid (#6).
To keep that coherent:

- **The grid behind is paged by surface.** On a local surface (Watchlist,
  Library) the grid is paged and scrolled to include the entry's row, which is
  locatable from the ADR 0019 snapshot under the active sort and filters. On
  the Catalog or a Share view the entry's position in the provider feed is not
  knowable without scanning, so the grid renders from the top.
- **A surface-URL history entry is synthesized** behind the overlay on direct
  load, so the browser back button closes the overlay to the grid rather than
  navigating out of the app — identical to the navigated-from-grid case.

## Consequences

- **URL shape is now part of the public surface.** Once URLs are
  out in the wild — bookmarked, DMed, embedded — changing the shape
  breaks them. Future routing changes must preserve this contract
  or ship redirects.
- **Path collisions with surface segments must be avoided.** No
  reserved sub-route under a surface (e.g. `/movies/catalog/new`)
  can ever be added without checking it cannot collide with an
  external id format. OpenLibrary uses an `OL` prefix; TMDB and
  IGDB are numeric; reserved English words are safe.
- **The Share variant lives under `/share/<token>/<external_id>`**
  so a Friend can be sent directly to a specific entry inside a
  Share, not just the Share root.
