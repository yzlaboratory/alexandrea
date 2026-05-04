# External rating coverage is asymmetric across providers; null is honest

The three upstream providers do not offer rating data of comparable
quality:

- **TMDB** publishes `vote_average` (0–10) for essentially every
  Movies and TV entry; coverage is high.
- **IGDB** publishes `total_rating` (0–100) for most Games entries;
  coverage is high.
- **OpenLibrary** has a `ratings` endpoint that returns a community
  average per work, but it is **not** prominently displayed on
  openlibrary.org and coverage is thin — many works have no
  community rating at all.

For Books we **use the OpenLibrary `ratings` endpoint when a value
is present, and treat absence as `null`** — never as `0`, never as a
silent default. The display in lists and detail overlays shows the
raw provider scale (e.g. `4.2/5 OpenLibrary`) when present and
nothing at all when absent — no placeholder, no apology line.

When the user sorts by **external rating**, entries with `null`
external rating are **excluded from the sort** (not appended at the
top, not appended at the bottom, not silently re-mapped to 0). This
matches the per-aspect Characteristic exclusion rule already
established in `CONTEXT.md` for Library filters: "missing means
invisible to that sort."

## Why not impute 0 (or the median) for missing OpenLibrary ratings?

- Imputing 0 sinks unrated books to the bottom of `external rating
  desc`, making them effectively undiscoverable via that sort.
  Imputing the median scrambles the sort order. Both behaviours
  silently mislead the user.
- The exclusion rule already exists for per-aspect Characteristics
  on the Library — applying the same rule to external rating keeps
  one mental model across the app: "missing means invisible to that
  sort," nothing more.

## Why not drop external rating for Books entirely?

- It would make `view-entry-detail.md` and `browse-catalog.md`
  divergent across media types in a deeper way than null-honesty.
  Some readers genuinely want the OpenLibrary rating where it
  exists; hiding it for everyone to avoid the asymmetry costs
  signal for no benefit beyond visual symmetry.

## Consequences

- **Books `external rating` sort silently shrinks the result set**
  to entries that have a community rating. The empty-result
  scenario in `browse-catalog.md` still applies if the shrink is
  total.
- **The other three media types are unaffected.** TMDB and IGDB
  ratings are dense; in practice no entry is excluded.
- **The provider adapter for OpenLibrary** must surface the rating
  endpoint's outcome alongside the entity-presence outcome from
  ADR 0003. A `transient_failure` on the rating endpoint must not
  cascade or degrade the entity render — it just means "no rating
  shown right now" for the duration of the failure.
