package dev.yzlaboratory.alexandrea.catalog;

import java.util.Locale;
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

    private final TmdbClient tmdbClient;
    private final OpenLibraryClient openLibraryClient;
    private final IgdbClient igdbClient;
    private final CatalogCache cache;
    private final ProviderCircuitBreaker circuitBreaker;

    public CatalogService(
        TmdbClient tmdbClient,
        OpenLibraryClient openLibraryClient,
        IgdbClient igdbClient,
        CatalogCache cache,
        ProviderCircuitBreaker circuitBreaker
    ) {
        this.tmdbClient = tmdbClient;
        this.openLibraryClient = openLibraryClient;
        this.igdbClient = igdbClient;
        this.cache = cache;
        this.circuitBreaker = circuitBreaker;
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
