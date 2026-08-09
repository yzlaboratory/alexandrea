package dev.yzlaboratory.alexandrea.catalog;

import org.springframework.stereotype.Service;

/**
 * The facade {@code CatalogController} calls: resolves {@code media_type} to
 * the right provider client, checks {@link CatalogCache} first (ADR 0007),
 * and falls through to the upstream call on a miss, caching both the page
 * and its entries. Only "movies" is wired to a real provider for this slice
 * (#37) — every other media type is #39's job, and #38 adds the
 * stale-cache-on-failure fallback and circuit breaker this doesn't have yet.
 */
@Service
public class CatalogService {

    private static final String MOVIES_MEDIA_TYPE = "movies";
    private static final String TMDB_PROVIDER = "tmdb";
    private static final String POPULAR_FEED = "popular";
    private static final String NO_FILTERS = "";
    private static final String DEFAULT_SORT = "default";

    private final TmdbClient tmdbClient;
    private final CatalogCache cache;

    public CatalogService(TmdbClient tmdbClient, CatalogCache cache) {
        this.tmdbClient = tmdbClient;
        this.cache = cache;
    }

    public CatalogPageResult browse(String mediaType, int page) {
        if (!MOVIES_MEDIA_TYPE.equals(mediaType)) {
            throw new UnsupportedCatalogMediaTypeException(mediaType);
        }
        return popularMovies(page);
    }

    private CatalogPageResult popularMovies(int page) {
        var key = CatalogCache.pageKey(TMDB_PROVIDER, MOVIES_MEDIA_TYPE, POPULAR_FEED, NO_FILTERS, DEFAULT_SORT, page);
        return cache.getOrComputePage(key, () -> fetchAndCache(page));
    }

    private CatalogPageResult fetchAndCache(int page) {
        var fetched = tmdbClient.popularMovies(page);
        for (var entry : fetched.entries()) {
            var entryKey = CatalogCache.entryKey(entry.provider(), entry.externalId(), entry.mediaType());
            cache.putEntry(entryKey, entry);
        }
        return fetched;
    }
}
