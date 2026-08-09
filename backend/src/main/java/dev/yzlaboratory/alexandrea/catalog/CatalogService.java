package dev.yzlaboratory.alexandrea.catalog;

import org.springframework.stereotype.Service;

/**
 * The facade {@code CatalogController} calls: resolves {@code media_type} to
 * the right provider client, checks {@link CatalogCache} first (ADR 0007),
 * and falls through to the upstream call on a miss, caching both the page
 * and its items. Only "movies" is wired to a real provider for this slice
 * (#37/#38) — every other media type is #39's job.
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

    private static final String MOVIES_MEDIA_TYPE = "movies";
    // Matches TmdbClient's own PROVIDER constant exactly (including case) —
    // CatalogItem.provider() is always "TMDB", so a lowercase "tmdb" here
    // would build page keys under a different string than the item keys
    // built from item.provider() in fetchAndCache(), even though both
    // conceptually name the same provider.
    private static final String TMDB_PROVIDER = "TMDB";
    private static final String POPULAR_FEED = "popular";
    private static final String NO_FILTERS = "";
    private static final String DEFAULT_SORT = "default";

    private final TmdbClient tmdbClient;
    private final CatalogCache cache;
    private final ProviderCircuitBreaker circuitBreaker;

    public CatalogService(TmdbClient tmdbClient, CatalogCache cache, ProviderCircuitBreaker circuitBreaker) {
        this.tmdbClient = tmdbClient;
        this.cache = cache;
        this.circuitBreaker = circuitBreaker;
    }

    public CatalogPageResult browse(String mediaType, int page) {
        if (!MOVIES_MEDIA_TYPE.equals(mediaType)) {
            throw new UnsupportedCatalogMediaTypeException(mediaType);
        }
        return popularMovies(page);
    }

    private CatalogPageResult popularMovies(int page) {
        var key = CatalogCache.pageKey(TMDB_PROVIDER, MOVIES_MEDIA_TYPE, POPULAR_FEED, NO_FILTERS, DEFAULT_SORT, page);
        try {
            return cache.getOrComputePage(key, () -> fetchThroughBreaker(page));
        } catch (CatalogUpstreamException upstreamFailure) {
            return cache.getPageRegardlessOfTtl(key).orElseThrow(() -> upstreamFailure);
        }
    }

    private CatalogPageResult fetchThroughBreaker(int page) {
        if (!circuitBreaker.allowRequest(TMDB_PROVIDER)) {
            throw new CatalogUpstreamException(TMDB_PROVIDER);
        }
        try {
            var fetched = fetchAndCache(page);
            circuitBreaker.recordSuccess(TMDB_PROVIDER);
            return fetched;
        } catch (CatalogUpstreamException upstreamFailure) {
            circuitBreaker.recordFailure(TMDB_PROVIDER);
            throw upstreamFailure;
        }
    }

    private CatalogPageResult fetchAndCache(int page) {
        var fetched = tmdbClient.popularMovies(page);
        for (var item : fetched.items()) {
            var itemKey = CatalogCache.itemKey(item.provider(), item.externalId(), item.mediaType());
            cache.putItem(itemKey, item);
        }
        return fetched;
    }
}
