package dev.yzlaboratory.alexandrea.catalog;

import dev.yzlaboratory.alexandrea.surface.SurfacePreference;
import dev.yzlaboratory.alexandrea.surface.SurfacePreferenceStore;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;
import org.springframework.stereotype.Service;

/**
 * The facade {@code CatalogController} calls: resolves {@code media_type} to
 * the right provider client, checks {@link CatalogCache} first (ADR 0007),
 * and falls through to the upstream call on a miss, caching both the page
 * and its items.
 *
 * <p>A miss is gated by {@link ProviderCircuitBreaker} and, on any failure
 * to reach the provider — whether the breaker is already open or the call
 * itself fails — falls through to whatever stale page is still on hand
 * (ADR 0015) before giving up with {@link CatalogUpstreamException}. ADR
 * 0015 also mentions a best-effort background refresh queued alongside the
 * stale response; this slice skips it; a synchronous fetch on the next
 * request remains correct, just not pre-warmed.
 */
@Service
public class CatalogService {

    private static final String POPULAR_FEED = "popular";
    // Prefixed rather than the bare query text: a popular-feed key's
    // feed/query slot (ADR 0007) is always exactly the literal "popular", so
    // no search query — however a user spells it, even literally "popular"
    // — can ever collide with it under this prefix.
    private static final String SEARCH_FEED_PREFIX = "search:";
    private static final String NO_FILTERS = "";
    private static final String DEFAULT_SORT = "default";
    private static final String CATALOG_SURFACE = "catalog";
    // ADR 0018: all four sorts are available for all four media types, so
    // (unlike filters) sort needs no per-media-type capability table — just
    // this one shared valid-key/valid-direction check.
    private static final Set<String> VALID_SORT_KEYS =
        Set.of("popularity", "release_date", "title", "external_rating");
    private static final Set<String> VALID_DIRECTIONS = Set.of("asc", "desc");

    private final TmdbClient tmdbClient;
    private final OpenLibraryClient openLibraryClient;
    private final IgdbClient igdbClient;
    private final CatalogCache cache;
    private final ProviderCircuitBreaker circuitBreaker;
    private final SurfacePreferenceStore surfacePreferenceStore;

    public CatalogService(
        TmdbClient tmdbClient,
        OpenLibraryClient openLibraryClient,
        IgdbClient igdbClient,
        CatalogCache cache,
        ProviderCircuitBreaker circuitBreaker,
        SurfacePreferenceStore surfacePreferenceStore
    ) {
        this.tmdbClient = tmdbClient;
        this.openLibraryClient = openLibraryClient;
        this.igdbClient = igdbClient;
        this.cache = cache;
        this.circuitBreaker = circuitBreaker;
        this.surfacePreferenceStore = surfacePreferenceStore;
    }

    /** Popular-feed convenience overload — equivalent to {@code browse(mediaType, null, page)}. */
    public CatalogPageResult browse(String mediaType, int page) {
        return browse(mediaType, null, page);
    }

    // The one dispatch point routing media_type (and, when present, a text
    // search) to its provider and the right endpoint per ADR 0018's
    // "nothing applied" / "text search active" rows — every media type
    // shares the exact same cache + circuit-breaker path below, so this
    // stays two small switches rather than four near-identical service
    // classes per state.
    public CatalogPageResult browse(String mediaType, String search, int page) {
        var query = normalizeSearch(search);
        return query != null ? searchFeedFor(mediaType, query, page) : popularFeedFor(mediaType, page);
    }

    /**
     * The sort-aware overload the Catalog controller calls: routes to the
     * search feed exactly as {@link #browse(String, String, int)} does, and
     * ignores {@code sortKey}/{@code sortDirection} while a search is active
     * — TMDB's and IGDB's search endpoints cannot honor an explicit sort at
     * all (ADR 0018's "Behavior under active text search"), and this issue
     * does not wire OpenLibrary's own ability to combine the two. Current
     * search is therefore preserved untouched across a sort change, and vice
     * versa. An unrecognised sort key or direction is dropped rather than
     * passed through (falls back to the popular feed), matching the same
     * "callers validate, invalid drops rather than errors" rule ADR 0025
     * sets for filters. A recognised sort persists to {@link
     * SurfacePreferenceStore} only once the page it produced was actually
     * served — an upstream failure or unsupported media type must not
     * persist a sort choice its own request never proved works.
     */
    public CatalogPageResult browse(
        String mediaType, String search, String sortKey, String sortDirection, long userId, int page
    ) {
        var query = normalizeSearch(search);
        if (query != null) {
            return searchFeedFor(mediaType, query, page);
        }
        if (!isValidSort(sortKey, sortDirection)) {
            return popularFeedFor(mediaType, page);
        }
        var result = sortedFeedFor(mediaType, sortKey, sortDirection, page);
        surfacePreferenceStore.upsert(userId, CATALOG_SURFACE, mediaType, sortKey, sortDirection, null);
        return result;
    }

    /** The current user's persisted Catalog sort for this media type, if they've ever set one (ADR 0025). */
    public Optional<SurfacePreference> sortPreference(long userId, String mediaType) {
        return surfacePreferenceStore.get(userId, CATALOG_SURFACE, mediaType);
    }

    private static boolean isValidSort(String sortKey, String sortDirection) {
        return sortKey != null && VALID_SORT_KEYS.contains(sortKey)
            && sortDirection != null && VALID_DIRECTIONS.contains(sortDirection);
    }

    // Lowercased and whitespace-collapsed, not just trimmed: "Blade Runner",
    // "blade runner", and "blade   runner" are the same search to a user,
    // and without this they'd fall into three distinct cache entries (and
    // cost three separate upstream calls) for one search. All three
    // providers' search relevance is already case-insensitive, so this
    // changes nothing about which results come back.
    private static String normalizeSearch(String search) {
        if (search == null) return null;
        var normalized = search.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private CatalogPageResult popularFeedFor(String mediaType, int page) {
        return switch (mediaType) {
            case TmdbClient.MOVIES_MEDIA_TYPE ->
                popularFeed(TmdbClient.PROVIDER, TmdbClient.MOVIES_MEDIA_TYPE, tmdbClient::popularMovies, page);
            case TmdbClient.TV_MEDIA_TYPE ->
                popularFeed(TmdbClient.PROVIDER, TmdbClient.TV_MEDIA_TYPE, tmdbClient::popularTv, page);
            case OpenLibraryClient.BOOKS_MEDIA_TYPE -> popularFeed(
                OpenLibraryClient.PROVIDER, OpenLibraryClient.BOOKS_MEDIA_TYPE, openLibraryClient::trendingBooks, page
            );
            case IgdbClient.GAMES_MEDIA_TYPE ->
                popularFeed(IgdbClient.PROVIDER, IgdbClient.GAMES_MEDIA_TYPE, igdbClient::popularGames, page);
            default -> throw new UnsupportedCatalogMediaTypeException(mediaType);
        };
    }

    private CatalogPageResult sortedFeedFor(String mediaType, String sortKey, String sortDirection, int page) {
        return switch (mediaType) {
            case TmdbClient.MOVIES_MEDIA_TYPE -> sortedFeed(
                TmdbClient.PROVIDER, TmdbClient.MOVIES_MEDIA_TYPE, sortKey, sortDirection,
                pageToFetch -> tmdbClient.discoverMovies(sortKey, sortDirection, pageToFetch), page
            );
            case TmdbClient.TV_MEDIA_TYPE -> sortedFeed(
                TmdbClient.PROVIDER, TmdbClient.TV_MEDIA_TYPE, sortKey, sortDirection,
                pageToFetch -> tmdbClient.discoverTv(sortKey, sortDirection, pageToFetch), page
            );
            case OpenLibraryClient.BOOKS_MEDIA_TYPE -> sortedFeed(
                OpenLibraryClient.PROVIDER, OpenLibraryClient.BOOKS_MEDIA_TYPE, sortKey, sortDirection,
                pageToFetch -> openLibraryClient.sortedBooks(sortKey, sortDirection, pageToFetch), page
            );
            case IgdbClient.GAMES_MEDIA_TYPE -> sortedFeed(
                IgdbClient.PROVIDER, IgdbClient.GAMES_MEDIA_TYPE, sortKey, sortDirection,
                pageToFetch -> igdbClient.discoverGames(sortKey, sortDirection, pageToFetch), page
            );
            default -> throw new UnsupportedCatalogMediaTypeException(mediaType);
        };
    }

    private CatalogPageResult searchFeedFor(String mediaType, String query, int page) {
        return switch (mediaType) {
            case TmdbClient.MOVIES_MEDIA_TYPE -> searchFeed(
                TmdbClient.PROVIDER, TmdbClient.MOVIES_MEDIA_TYPE, query, pageToFetch -> tmdbClient.searchMovies(query, pageToFetch), page
            );
            case TmdbClient.TV_MEDIA_TYPE -> searchFeed(
                TmdbClient.PROVIDER, TmdbClient.TV_MEDIA_TYPE, query, pageToFetch -> tmdbClient.searchTv(query, pageToFetch), page
            );
            case OpenLibraryClient.BOOKS_MEDIA_TYPE -> searchFeed(
                OpenLibraryClient.PROVIDER, OpenLibraryClient.BOOKS_MEDIA_TYPE, query,
                pageToFetch -> openLibraryClient.search(query, pageToFetch), page
            );
            case IgdbClient.GAMES_MEDIA_TYPE -> searchFeed(
                IgdbClient.PROVIDER, IgdbClient.GAMES_MEDIA_TYPE, query, pageToFetch -> igdbClient.search(query, pageToFetch), page
            );
            default -> throw new UnsupportedCatalogMediaTypeException(mediaType);
        };
    }

    private CatalogPageResult popularFeed(String provider, String mediaType, IntFunction<CatalogPageResult> fetchPage, int page) {
        var key = CatalogCache.pageKey(provider, mediaType, POPULAR_FEED, NO_FILTERS, DEFAULT_SORT, page);
        return cachedFeed(provider, key, fetchPage, page);
    }

    private CatalogPageResult searchFeed(
        String provider, String mediaType, String query, IntFunction<CatalogPageResult> fetchPage, int page
    ) {
        var key = CatalogCache.pageKey(provider, mediaType, SEARCH_FEED_PREFIX + query, NO_FILTERS, DEFAULT_SORT, page);
        return cachedFeed(provider, key, fetchPage, page);
    }

    // A "popular sorted by X" page is cached separately from both the plain
    // popular feed and any search — it shares the "popular" feed/query slot
    // (it's still the unfiltered, unsearched browse) but carries the real
    // sort in the slot popularFeed always fills with the DEFAULT_SORT
    // placeholder, so choosing a sort can never collide with — or be served
    // by — the default popular page's cache entry.
    private CatalogPageResult sortedFeed(
        String provider, String mediaType, String sortKey, String sortDirection,
        IntFunction<CatalogPageResult> fetchPage, int page
    ) {
        var key = CatalogCache.pageKey(provider, mediaType, POPULAR_FEED, NO_FILTERS, sortKey + ":" + sortDirection, page);
        return cachedFeed(provider, key, fetchPage, page);
    }

    private CatalogPageResult cachedFeed(String provider, String key, IntFunction<CatalogPageResult> fetchPage, int page) {
        try {
            return cache.getOrComputePage(key, () -> fetchThroughBreaker(provider, fetchPage, page));
        } catch (CatalogUpstreamException upstreamFailure) {
            return cache.getPageRegardlessOfTtl(key).orElseThrow(() -> upstreamFailure);
        }
    }

    private CatalogPageResult fetchThroughBreaker(String provider, IntFunction<CatalogPageResult> fetchPage, int page) {
        if (!circuitBreaker.allowRequest(provider)) {
            throw new CatalogUpstreamException(provider);
        }
        try {
            var fetched = fetchAndCache(fetchPage, page);
            circuitBreaker.recordSuccess(provider);
            return fetched;
        } catch (RuntimeException failure) {
            // Any failure reaching the provider counts against the breaker,
            // not just CatalogUpstreamException — narrowing this to just that
            // type would leave the breaker's bookkeeping (and, if this call
            // was the half-open probe, its claimed-probe state) never
            // updated whenever some other failure mode slips through.
            circuitBreaker.recordFailure(provider);
            throw failure instanceof CatalogUpstreamException
                ? failure
                : new CatalogUpstreamException(provider, failure);
        }
    }

    private CatalogPageResult fetchAndCache(IntFunction<CatalogPageResult> fetchPage, int page) {
        var fetched = fetchPage.apply(page);
        for (var item : fetched.items()) {
            var itemKey = CatalogCache.itemKey(item.provider(), item.externalId(), item.mediaType());
            cache.putItem(itemKey, item);
        }
        return fetched;
    }
}
