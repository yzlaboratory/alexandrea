# ADR 0002: Source Movie and Series Metadata from a Public/Open Database

- **Status:** Accepted (provider choice: TMDB primary, Wikidata secondary)
- **Date:** 2026-04-15
- **Deciders:** Kira
- **Supersedes:** —
- **Superseded by:** —
- **Related:** ADR 0001 (AWS infrastructure), `specs/02-content-metadata.md`

## Context

The product (see `specs/00-overview.md`, `specs/02-content-metadata.md`) needs metadata for every movie and series the user might browse, search, list, or watch. Specifically:

- Title, year, runtime, genre, content rating, synopsis.
- Poster art and backdrops.
- Cast and crew (director / creator + top cast).
- Series structure (seasons → episodes), with per-episode title, number, air date, runtime, synopsis.
- Cross-reference identifiers so we can hop between providers (e.g., to fetch a Rotten Tomatoes score later, or to import from Letterboxd / Trakt / IMDb per `specs/04-watchlists.md`).
- Streaming availability, regionally accurate.

Building this catalog ourselves is out of the question — there are millions of titles, episode lists are constantly updated, and posters need careful sourcing and licensing. We need a third-party data provider.

The provider must:

1. **Cover both movies and series** with episode-level depth.
2. **Ship posters and backdrops**, with a CDN or licensable image URLs.
3. **Allow commercial use**, eventually — even if the early-access phase is small, we don't want to pick a stack we have to rip out the moment a single dollar changes hands.
4. **Be financially realistic** for a two-person team. Six-figure enterprise contracts are out.
5. **Have stable, well-documented APIs** — bonus points for active community SDKs.
6. **Provide cross-reference IDs** to IMDb, TMDB, TVDB, Wikidata, etc., so we're not locked in.

## Decision

**Use TMDB (The Movie Database) as the primary metadata provider, with Wikidata as a secondary open-data source for stable IDs, cross-references, and gap-filling.**

Concretely:

- **TMDB** powers titles, posters, backdrops, synopses, runtime, cast, season/episode lists, certifications, and (via JustWatch integration) streaming availability. We attribute TMDB on every surface that displays its data, per their terms.
- **Wikidata** is queried (SPARQL) on a slower cadence to fetch and store stable cross-reference IDs (TMDB ID, IMDb ID, TVDB ID, Wikidata QID) for every title in our library. This lets us migrate or layer providers later without touching user data.
- **OMDb** is permitted as a *bootstrap-only*, non-commercial fallback during local development and pre-launch QA, but is removed before the first paying user lands (its CC BY-NC license forbids commercial use).
- **Rotten Tomatoes** has no public API. The Tomatometer + Audience Score that the spec calls for are not available from any open source. This ADR explicitly defers the RT decision to a follow-up ADR (see "Follow-ups").
- Before flipping to commercial use, we **subscribe to TMDB's standard commercial plan** (~$149/month, ~$1,800/year for companies with under $1M revenue). See "Pricing estimations" below for the full breakdown.

## Provider research

The viable candidates and how they stack up against the criteria above:

### TMDB (The Movie Database) — chosen primary

- **Coverage:** Excellent for both movies and TV. Community-curated since 2008. Episode-level data, posters in many resolutions and locales.
- **API:** Modern REST API, generous rate limits in practice, well-maintained client libraries in every major language.
- **Licensing:** Free for non-commercial use with attribution. **Commercial use requires a written agreement** with TMDB; pricing is by quote and historically reasonable for small products.
- **Streaming availability:** Bundled via TMDB's JustWatch partnership — exactly what the spec needs.
- **Cross-references:** Provides IMDb ID, TVDB ID, Wikidata QID, Facebook/Instagram/Twitter handles, and more on most titles.
- **Risk:** Single-vendor dependency, and the commercial pricing is opaque until you ask. Mitigated by storing Wikidata QIDs so we can migrate.

### Wikidata — chosen secondary

- **Coverage:** Broad but uneven. Most major releases are present with TMDB/IMDb/TVDB IDs and a Wikipedia summary. Smaller or newer titles often lack episode-level detail.
- **API:** SPARQL endpoint at `query.wikidata.org`, plus a REST entity API. Free and open.
- **Licensing:** **CC0 (public domain)** — fully open, fully commercial-safe.
- **Posters:** Limited and inconsistent quality; not a substitute for TMDB artwork.
- **Why we use it anyway:** It is the most reliable source of *stable cross-reference IDs* in the open data ecosystem, and it costs nothing. Storing the Wikidata QID for every title in our library gives us an emergency exit from any commercial provider.

### OMDb — bootstrap only, then removed

- **Coverage:** Solid for movies, weaker on episode-level series data.
- **API:** Simple, easy to integrate.
- **Licensing:** **CC BY-NC 4.0** — non-commercial only. Disqualified for production.
- **Pricing:** Free 1,000 req/day, paid tiers raise the cap, but the license is the blocker, not the rate limit.

### IMDb (official) — rejected

- **Coverage:** Best in industry, including ratings.
- **Licensing:** Available **only through AWS Data Exchange** at enterprise pricing reportedly starting around six figures plus metered charges. Out of scope for a two-person team.

### TheTVDB v4 — rejected for primary, possible future supplement

- **Coverage:** Strongest on TV series, weaker for film.
- **Licensing:** Two paths. (a) Negotiated commercial license, priced by usage and parent-company revenue; FOSS-friendly discounts available. (b) **User-subscription model** where each end user must hold a $12/year TheTVDB subscription — incompatible with our consumer product.
- **Why not primary:** Movie coverage is thinner than TMDB, and the user-subscription option doesn't fit our model.
- **Possible later:** If TMDB's series episode data has gaps for a specific show our users care about, a negotiated TVDB key is a reasonable supplement.

### Trakt — rejected as primary, candidate for import only

- Trakt is more of a tracking layer than a metadata catalog (it largely re-uses TMDB and TVDB data).
- API access requires per-user OAuth tokens, which is fine for **importing** a user's existing Trakt history (covered in `specs/04-watchlists.md`) but wrong as a primary catalog.

### Building our own from public datasets — rejected

- The IMDb Non-Commercial Datasets are public but exclude posters and forbid commercial use.
- Combining Wikidata + scraped Wikipedia + open poster sources is technically possible but is months of work, fragile, and legally murky on artwork.
- Not worth the effort versus paying TMDB.

## Pricing estimations

All figures are best-effort as of April 2026, in USD, and **should be confirmed by quote** before any financial commitment. Where a vendor doesn't publish prices, the range reflects what small-to-mid commercial projects have publicly reported.

### TMDB

| Tier | Price | Notes |
| --- | --- | --- |
| Free (non-commercial) | **$0** | Attribution required. Not usable once the product earns any revenue. |
| Commercial — Standard | **~$149/month (~$1,788/year)** | Available to companies with **under $1M annual revenue**. Self-serve subscription. This is our expected starting tier for years 1–2. |
| Commercial — Enterprise | **Custom quote** | Required above $1M annual revenue. No public pricing; sales contact required. |

**Operational add-ons (TMDB):** none directly. Image bandwidth via TMDB's CDN is free, but we will re-cache to our own S3 + CloudFront (see ADR 0001) — those costs sit on our AWS bill, not TMDB's.

**Expected year-1 spend on TMDB:** **~$0 during pre-launch (non-commercial), ~$1,800 the year we monetize.**

### Wikidata

| Tier | Price | Notes |
| --- | --- | --- |
| Public SPARQL endpoint (`query.wikidata.org`) | **$0** | CC0 data. Rate-limited and unsuitable for production-scale querying. |
| Self-hosted Wikidata mirror | **~$50–$300/month** | If we ever need heavy querying, we run a Blazegraph or QLever instance on a single EC2 (e.g., `m6i.xlarge` ~$140/month + ~$30/month storage). For our envisioned usage (occasional cross-reference lookups during nightly refresh), the public endpoint suffices and costs $0. |

**Expected year-1 spend on Wikidata:** **$0.**

### OMDb (bootstrap-only — removed before launch)

| Tier | Price | Notes |
| --- | --- | --- |
| Free | **$0** | 1,000 requests/day, hard cap. License is CC BY-NC 4.0 — non-commercial only. |
| Patreon-supported | **$1–$10/month** | Higher rate limits via Brian Fritz's Patreon. License remains non-commercial. |

**Expected total spend on OMDb:** **$0–$120 during early development**, then removed.

### IMDb (rejected)

| Product | Price | Notes |
| --- | --- | --- |
| IMDb Essential Metadata for Movies/TV/OTT (AWS Data Exchange) | **$150,000/year + metered** | 12-month subscription. |
| IMDb + Box Office Mojo bundle | **$400,000/year + metered** | 12-month subscription. |
| IMDb Complete Dataset | **Custom quote** | Free trial available for evaluation only. |

**Why this number disqualifies IMDb:** at the spec's user scale, the per-user cost would be in the four-to-five-figure range. Not viable.

### TheTVDB v4 (rejected as primary, possible later supplement)

| Path | Price | Notes |
| --- | --- | --- |
| Negotiated commercial license | **Opaque; estimated low four figures/year for small projects** | Priced by usage, parent-company revenue, and use case. FOSS discounts available. No public price sheet. Anecdotal community reports suggest indie projects start around **~$1,000–$3,000/year**, but this is **not authoritative** — quote required. |
| User-supported subscription | **$12/year × every end user** | Forces our users to hold their own TheTVDB sub. Incompatible with our consumer model. |

**Expected spend if added later as a TV supplement:** **~$1,000–$3,000/year**, pending quote.

### Trakt (import only, not catalog)

| Tier | Price | Notes |
| --- | --- | --- |
| Trakt API | **$0** | Free for read access via per-user OAuth. Only used for one-time import flows (`specs/04-watchlists.md`), not as a metadata catalog. |

**Expected spend on Trakt:** **$0.**

### Self-built catalog (rejected)

| Cost type | Estimate | Notes |
| --- | --- | --- |
| Engineering effort | **~3–6 person-months upfront, then ongoing maintenance** | Combining IMDb non-commercial datasets + Wikipedia scraping + open poster sources. |
| Hosting | **~$100–$500/month** | Depending on dataset size and indexing strategy. |
| Legal exposure | **Material** | Poster artwork licensing is the main risk. |

**Why rejected on cost alone:** at a fully-loaded developer cost of ~$10k/month, three months of build is ~$30k — more than 15 years of TMDB's commercial subscription.

### Pricing summary

| Provider | Year-1 cost (pre-launch, non-commercial) | Year-1 cost (post-launch, commercial) |
| --- | --- | --- |
| **TMDB** (chosen primary) | **$0** | **~$1,800** |
| **Wikidata** (chosen secondary) | **$0** | **$0** |
| OMDb (bootstrap, then dropped) | **$0–$120** | **$0** (removed) |
| IMDb | n/a | **$150,000+** |
| TheTVDB (possible later) | n/a | **~$1,000–$3,000** (estimated) |
| Trakt (import only) | $0 | $0 |
| Self-build | $30k+ effort | $30k+ effort + $1.2k–6k/year hosting |

**Combined expected metadata-provider cost in year 1 of commercial operation: ~$1,800.** Adding TheTVDB later as a supplement would put us in the **~$3,000–$5,000/year** range. This is comfortably within the budget assumption baked into ADR 0001's "stay cheap during early-access" goal.

## Rationale for choosing TMDB + Wikidata

- **TMDB** is the single best fit on coverage, API quality, posters, and streaming-availability data; it is the de-facto standard for indie media products.
- The **commercial license is realistic** for a small product, where IMDb's official API is not.
- **Wikidata** is genuinely open, costs nothing, and provides the cross-reference IDs that protect us from lock-in. Storing Wikidata QIDs costs us almost nothing now and buys real optionality later.
- **OMDb's NC license** would have been fine forever if this stayed a personal tool, but the spec is built for a real product, so we treat any non-commercial license as a non-starter past launch.

## Consequences

### Positive

- Fast time to value: TMDB's API plus a CDN for cached posters covers ~95% of the metadata work in `specs/02-content-metadata.md` out of the box.
- Streaming availability comes free via TMDB's JustWatch integration — no second contract needed.
- Wikidata cross-references mean we can switch primary providers without rewriting our internal title model.
- Attribution is straightforward and adds a small "Powered by TMDB" footer mark, consistent with industry norms.

### Negative

- **Vendor concentration risk on TMDB.** Mitigated by Wikidata IDs and a well-defined internal `Title` model that hides provider details from the rest of the app.
- **Commercial pricing is opaque** until we ask. We need to get a quote before committing to a launch date.
- **Rotten Tomatoes is not solvable** by this ADR. The spec promises RT scores prominently; we either (a) reach a partnership / data deal with Fandango/RT, (b) substitute another rating (TMDB user score, IMDb-via-OMDb-bootstrap), or (c) reduce RT prominence in the spec. This is a real open question that needs its own ADR before launch.
- **Image hotlinking from TMDB's CDN works**, but for resilience and speed we'll re-cache poster assets to our own S3 + CloudFront setup (see ADR 0001), respecting TMDB's caching guidelines.

### Operational notes

- Build an internal `Title` aggregate that holds: our internal ID, TMDB ID, IMDb ID, TVDB ID, Wikidata QID, plus the rendered metadata. The rest of the system references the internal ID only.
- Nightly background job (Lambda → Aurora) refreshes metadata for titles in any user's library. Per-title TTL is 24 hours, with a longer TTL (7 days) for titles last touched >6 months ago to reduce API load.
- Respect TMDB's rate limits and attribution rules; surface attribution on the title detail page footer and in the About page.
- Run a one-time backfill against Wikidata to populate QIDs for the initial seed catalog.

## Follow-ups

- **ADR 0003 (planned): Rotten Tomatoes data sourcing.** Decide whether to pursue an RT/Fandango partnership, fall back to TMDB user score, or revise the spec.
- **ADR 0004 (planned): Title model and ID strategy.** Lock in the internal `Title` aggregate and migration story.
- Get a written commercial-use quote from TMDB at least 60 days before any monetization or public launch.

## Sources

- [TMDB API Terms of Use](https://www.themoviedb.org/api-terms-of-use)
- [TMDB API for Business](https://www.themoviedb.org/api-for-business)
- [TMDB Talk — Commercial license clarification](https://www.themoviedb.org/talk/681a1956bbbf46d7f66404f9)
- [TMDB Talk — Commercial usage pricing thread](https://www.themoviedb.org/talk/622b91d0d236e60045f62782)
- [TMDB Talk — Pricing model for commercial use](https://www.themoviedb.org/talk/5cdcd9b59251416e32cfc9ac)
- [OMDb API key page](https://www.omdbapi.com/apikey.aspx)
- [OMDb Patreon (Brian Fritz)](https://www.patreon.com/omdb)
- [Zuplo — Best Movie Database API: IMDb vs TMDb vs OMDb](https://zuplo.com/learning-center/best-movie-api-imdb-vs-omdb-vs-tmdb)
- [IMDb Developer Portal](https://developer.imdb.com/)
- [AWS Marketplace — IMDb Essential Metadata for Movies/TV/OTT (API)](https://aws.amazon.com/marketplace/pp/prodview-wdqq4hg3bcbws)
- [AWS Marketplace — IMDb Essential Metadata for Movies/TV/OTT (Bulk)](https://aws.amazon.com/marketplace/pp/prodview-yeuyizioqmfsy)
- [TheTVDB API information](https://www.thetvdb.com/api-information)
- [TheTVDB Licensed vs. User-supported API Keys](https://support.thetvdb.com/kb/faq.php?id=62)
- [TheTVDB Subscribe page](https://thetvdb.com/subscribe)
- [Wikidata Query Service (SPARQL)](https://query.wikidata.org/)
- [Wikidata SPARQL tutorial](https://www.wikidata.org/wiki/Wikidata:SPARQL_tutorial)
