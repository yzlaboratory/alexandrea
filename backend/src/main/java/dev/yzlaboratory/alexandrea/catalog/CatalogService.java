package dev.yzlaboratory.alexandrea.catalog;

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

    // The one dispatch point routing media_type to its provider and popular
    // feed (ADR 0018's "nothing applied" row) — every media type shares the
    // exact same cache + circuit-breaker path below, so this stays one small
    // switch rather than four near-identical service classes.
    public CatalogPageResult browse(String mediaType, int page) {
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

    private CatalogPageResult popularFeed(String provider, String mediaType, IntFunction<CatalogPageResult> fetchPage, int page) {
        var key = CatalogCache.pageKey(provider, mediaType, POPULAR_FEED, NO_FILTERS, DEFAULT_SORT, page);
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
