package dev.yzlaboratory.alexandrea.catalog;

import dev.yzlaboratory.alexandrea.surface.SurfacePreference;
import dev.yzlaboratory.alexandrea.surface.SurfacePreferenceStore;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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

    private static final Logger LOG = LoggerFactory.getLogger(CatalogService.class);

    private static final String POPULAR_FEED = "popular";
    // Prefixed rather than the bare query text: a popular-feed key's
    // feed/query slot (ADR 0007) is always exactly the literal "popular", so
    // no search query — however a user spells it, even literally "popular"
    // — can ever collide with it under this prefix.
    private static final String SEARCH_FEED_PREFIX = "search:";
    private static final String NO_FILTERS = "";
    private static final String DEFAULT_SORT = "default";
    private static final String CATALOG_SURFACE = "catalog";
    private static final String GENRE_FIELD = "genre";
    private static final Set<String> SUPPORTED_MEDIA_TYPES = Set.of(
        TmdbClient.MOVIES_MEDIA_TYPE, TmdbClient.TV_MEDIA_TYPE, OpenLibraryClient.BOOKS_MEDIA_TYPE, IgdbClient.GAMES_MEDIA_TYPE
    );
    // ADR 0018: all four sorts are available for all four media types, so
    // (unlike filters) sort needs no per-media-type capability table — just
    // this one shared valid-key/valid-direction check.
    private static final Set<String> VALID_SORT_KEYS = Set.of(
        CatalogSort.POPULARITY, CatalogSort.RELEASE_DATE, CatalogSort.TITLE, CatalogSort.EXTERNAL_RATING
    );
    private static final Set<String> VALID_DIRECTIONS = Set.of(CatalogSort.ASCENDING, CatalogSort.DESCENDING);

    private final TmdbClient tmdbClient;
    private final OpenLibraryClient openLibraryClient;
    private final IgdbClient igdbClient;
    private final CatalogCache cache;
    private final ProviderCircuitBreaker circuitBreaker;
    private final SurfacePreferenceStore surfacePreferenceStore;
    private final GenreVocabulary genreVocabulary;
    private final ObjectMapper objectMapper;

    public CatalogService(
        TmdbClient tmdbClient,
        OpenLibraryClient openLibraryClient,
        IgdbClient igdbClient,
        CatalogCache cache,
        ProviderCircuitBreaker circuitBreaker,
        SurfacePreferenceStore surfacePreferenceStore,
        GenreVocabulary genreVocabulary,
        ObjectMapper objectMapper
    ) {
        this.tmdbClient = tmdbClient;
        this.openLibraryClient = openLibraryClient;
        this.igdbClient = igdbClient;
        this.cache = cache;
        this.circuitBreaker = circuitBreaker;
        this.surfacePreferenceStore = surfacePreferenceStore;
        this.genreVocabulary = genreVocabulary;
        this.objectMapper = objectMapper;
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
     * The sort- and filter-aware overload the Catalog controller calls:
     * routes to the search feed exactly as {@link #browse(String, String,
     * int)} does, and ignores {@code sortKey}/{@code sortDirection}/{@code
     * genre} while a search is active — TMDB's and IGDB's search endpoints
     * cannot honor an explicit sort or {@code with_genres}/{@code where
     * genres} at all (ADR 0018's "Behavior under active text search"), and
     * this issue does not wire OpenLibrary's own ability to combine the two.
     * Current search is therefore preserved untouched across a sort or
     * filter change, and vice versa.
     *
     * <p>An unrecognised sort key/direction, or {@code genre} being {@code
     * null} (the caller didn't mention genre at all) or an unrecognised
     * value, falls back to what's already persisted for that piece, so that
     * a request changing only the sort can't silently clobber a
     * previously-chosen genre and vice versa (the read-merge-write ADR
     * 0025's store Javadoc requires, since {@link
     * SurfacePreferenceStore#upsert} replaces the whole row). {@code genre}
     * being the <em>empty string</em> is different from {@code null}: it's
     * the frontend's explicit "no genre selected" signal (a deselected
     * filter chip) and clears rather than falls back — {@code
     * CatalogController} can make this distinction because HTTP already
     * distinguishes an absent query param from a present-but-empty one. The
     * combined row that results — real or persisted-fallback sort, real,
     * cleared, or persisted-fallback genre — is what actually gets upserted
     * and what the page is fetched with.
     */
    public CatalogPageResult browse(
        String mediaType, String search, String sortKey, String sortDirection, String genre, long userId, int page
    ) {
        var query = normalizeSearch(search);
        if (query != null) {
            return searchFeedFor(mediaType, query, page);
        }
        if (!SUPPORTED_MEDIA_TYPES.contains(mediaType)) {
            throw new UnsupportedCatalogMediaTypeException(mediaType);
        }
        var genreExplicitlyCleared = "".equals(genre);
        if (!isValidSort(sortKey, sortDirection) && !isValidGenre(mediaType, genre) && !genreExplicitlyCleared) {
            return popularFeedFor(mediaType, page);
        }

        var existing = surfacePreferenceStore.get(userId, CATALOG_SURFACE, mediaType);
        var resolvedSort = resolveSort(sortKey, sortDirection, existing);
        var resolvedGenre = genreExplicitlyCleared ? null : resolveGenre(mediaType, genre, existing);
        var fetchSortKey = resolvedSort.key() != null ? resolvedSort.key() : CatalogSort.POPULARITY;
        var fetchSortDirection = resolvedSort.direction() != null ? resolvedSort.direction() : CatalogSort.DESCENDING;

        var result = filteredFeedFor(mediaType, fetchSortKey, fetchSortDirection, resolvedGenre, page);
        surfacePreferenceStore.upsert(
            userId, CATALOG_SURFACE, mediaType, resolvedSort.key(), resolvedSort.direction(), encodeFilters(resolvedGenre)
        );
        return result;
    }

    /** The current user's persisted Catalog sort and genre filter for this media type, if ever set (ADR 0025). */
    public CatalogPreference preference(long userId, String mediaType) {
        var stored = surfacePreferenceStore.get(userId, CATALOG_SURFACE, mediaType);
        var genre = stored.map(SurfacePreference::filters).map(this::decodeGenre)
            .filter(value -> isValidGenre(mediaType, value))
            .orElse(null);
        return new CatalogPreference(
            stored.map(SurfacePreference::sortKey).orElse(null),
            stored.map(SurfacePreference::sortDirection).orElse(null),
            genre
        );
    }

    /**
     * Which filters {@code FilterControls} should offer for this media type
     * right now (ADR 0018's per-type capability table) — currently just
     * genre, keyed so #43 can add a Books entry to this same map without
     * the frontend or this method's callers changing shape. Omits genre
     * (rather than failing the whole browse response) when the vocabulary
     * is temporarily unreachable.
     */
    public Map<String, List<CatalogFilterOption>> availableFilters(String mediaType) {
        if (!genreVocabulary.supports(mediaType)) {
            return Map.of();
        }
        try {
            return Map.of(GENRE_FIELD, genreVocabulary.genresFor(mediaType));
        } catch (CatalogUpstreamException e) {
            LOG.warn("Genre vocabulary temporarily unavailable for {}; omitting it from the capability payload", mediaType, e);
            return Map.of();
        }
    }

    private record ResolvedSort(String key, String direction) {
        static final ResolvedSort NONE = new ResolvedSort(null, null);
    }

    private ResolvedSort resolveSort(String sortKey, String sortDirection, Optional<SurfacePreference> existing) {
        if (isValidSort(sortKey, sortDirection)) {
            return new ResolvedSort(sortKey, sortDirection);
        }
        return existing
            .filter(preference -> isValidSort(preference.sortKey(), preference.sortDirection()))
            .map(preference -> new ResolvedSort(preference.sortKey(), preference.sortDirection()))
            .orElse(ResolvedSort.NONE);
    }

    private String resolveGenre(String mediaType, String genre, Optional<SurfacePreference> existing) {
        if (isValidGenre(mediaType, genre)) {
            return genre;
        }
        var persistedGenre = existing.map(SurfacePreference::filters).map(this::decodeGenre).orElse(null);
        return isValidGenre(mediaType, persistedGenre) ? persistedGenre : null;
    }

    private static boolean isValidSort(String sortKey, String sortDirection) {
        return sortKey != null && VALID_SORT_KEYS.contains(sortKey)
            && sortDirection != null && VALID_DIRECTIONS.contains(sortDirection);
    }

    private boolean isValidGenre(String mediaType, String genre) {
        if (genre == null || genre.isBlank() || !genreVocabulary.supports(mediaType)) {
            return false;
        }
        try {
            return genreVocabulary.genresFor(mediaType).stream().anyMatch(option -> option.value().equals(genre));
        } catch (CatalogUpstreamException e) {
            LOG.warn("Could not verify genre {} for {} against a temporarily-unavailable vocabulary; dropping it", genre, mediaType, e);
            return false;
        }
    }

    private String encodeFilters(String genre) {
        if (genre == null) {
            return null;
        }
        return objectMapper.createObjectNode().put(GENRE_FIELD, genre).toString();
    }

    private String decodeGenre(String filtersJson) {
        try {
            return objectMapper.readTree(filtersJson).path(GENRE_FIELD).asString(null);
        } catch (JacksonException e) {
            LOG.warn("Could not parse persisted catalog filters {}; treating as none", filtersJson, e);
            return null;
        }
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

    private CatalogPageResult filteredFeedFor(String mediaType, String sortKey, String sortDirection, String genre, int page) {
        return switch (mediaType) {
            case TmdbClient.MOVIES_MEDIA_TYPE -> filteredFeed(
                TmdbClient.PROVIDER, TmdbClient.MOVIES_MEDIA_TYPE, sortKey, sortDirection, genre,
                pageToFetch -> tmdbClient.discoverMovies(sortKey, sortDirection, genre, pageToFetch), page
            );
            case TmdbClient.TV_MEDIA_TYPE -> filteredFeed(
                TmdbClient.PROVIDER, TmdbClient.TV_MEDIA_TYPE, sortKey, sortDirection, genre,
                pageToFetch -> tmdbClient.discoverTv(sortKey, sortDirection, genre, pageToFetch), page
            );
            // Books has no genre entry in GenreVocabulary yet (#43), so
            // resolveGenre never produces a non-null value for this
            // media type — sortedBooks needs no genre param of its own.
            case OpenLibraryClient.BOOKS_MEDIA_TYPE -> filteredFeed(
                OpenLibraryClient.PROVIDER, OpenLibraryClient.BOOKS_MEDIA_TYPE, sortKey, sortDirection, genre,
                pageToFetch -> openLibraryClient.sortedBooks(sortKey, sortDirection, pageToFetch), page
            );
            case IgdbClient.GAMES_MEDIA_TYPE -> filteredFeed(
                IgdbClient.PROVIDER, IgdbClient.GAMES_MEDIA_TYPE, sortKey, sortDirection, genre,
                pageToFetch -> igdbClient.discoverGames(sortKey, sortDirection, genre, pageToFetch), page
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

    // A "popular sorted by X, optionally filtered by genre Y" page is cached
    // separately from both the plain popular feed and any search — it
    // shares the "popular" feed/query slot (it's still the unfiltered-by-
    // search browse) but carries the real sort and genre in the slots
    // popularFeed always fills with DEFAULT_SORT/NO_FILTERS, so choosing a
    // sort or a genre can never collide with — or be served by — the
    // default popular page's cache entry, and a genre change is its own
    // distinct cache entry from the same sort with no genre.
    private CatalogPageResult filteredFeed(
        String provider, String mediaType, String sortKey, String sortDirection, String genre,
        IntFunction<CatalogPageResult> fetchPage, int page
    ) {
        var filters = genre != null ? GENRE_FIELD + ":" + genre : NO_FILTERS;
        var key = CatalogCache.pageKey(provider, mediaType, POPULAR_FEED, filters, sortKey + ":" + sortDirection, page);
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
