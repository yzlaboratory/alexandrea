package dev.yzlaboratory.alexandrea.catalog;

import dev.yzlaboratory.alexandrea.surface.SurfacePreference;
import dev.yzlaboratory.alexandrea.surface.SurfacePreferenceStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
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
    // ADR 0018's capability table: runtime is Movies/TV only (TMDB
    // with_runtime.gte/.lte); page count is Books only (OpenLibrary
    // number_of_pages_median). Games gets neither.
    private static final Set<String> RUNTIME_MEDIA_TYPES = Set.of(TmdbClient.MOVIES_MEDIA_TYPE, TmdbClient.TV_MEDIA_TYPE);
    private static final Set<String> PAGE_COUNT_MEDIA_TYPES = Set.of(OpenLibraryClient.BOOKS_MEDIA_TYPE);
    // ADR 0018's "Behavior under active text search": TMDB's /search/movie
    // and /search/tv accept only the query itself, so every Movies/TV
    // filter is disabled alongside sort — browse() never even resolves
    // them for these two media types, it goes straight to searchFeedFor.
    // IGDB's search clause combines with a `where` filter fine but not
    // with an explicit `sort`, so Games disables sort only (its filters
    // stay in SEARCH_COMBINES_SORT_OR_FILTERS_MEDIA_TYPES below).
    // OpenLibrary's /search.json already combines q= with sort= and every
    // field filter (see OpenLibraryClient#sortedBooks), so Books disables
    // nothing.
    private static final Set<String> MOVIES_TV_FILTERS_DISABLED_WHILE_SEARCHING =
        Set.of(CatalogFilterKeys.GENRE, CatalogFilterKeys.ORIGINAL_LANGUAGE, CatalogFilterKeys.RUNTIME);
    private static final Set<String> SEARCH_COMBINES_SORT_OR_FILTERS_MEDIA_TYPES =
        Set.of(IgdbClient.GAMES_MEDIA_TYPE, OpenLibraryClient.BOOKS_MEDIA_TYPE);
    private static final Set<String> SEARCH_DISABLES_SORT_MEDIA_TYPES = Set.of(IgdbClient.GAMES_MEDIA_TYPE);
    private static final TypeReference<Map<String, String>> FILTERS_JSON_TYPE = new TypeReference<>() {};

    private final TmdbClient tmdbClient;
    private final OpenLibraryClient openLibraryClient;
    private final IgdbClient igdbClient;
    private final CatalogCache cache;
    private final ProviderCircuitBreaker circuitBreaker;
    private final SurfacePreferenceStore surfacePreferenceStore;
    private final GenreVocabulary genreVocabulary;
    private final ObjectMapper objectMapper;
    private final List<CatalogFilterField> filterFields;

    public CatalogService(
        TmdbClient tmdbClient,
        OpenLibraryClient openLibraryClient,
        IgdbClient igdbClient,
        CatalogCache cache,
        ProviderCircuitBreaker circuitBreaker,
        SurfacePreferenceStore surfacePreferenceStore,
        GenreVocabulary genreVocabulary,
        LanguageVocabulary languageVocabulary,
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
        this.filterFields = List.of(
            new CatalogFilterField(CatalogFilterKeys.GENRE, genreVocabulary::supports, genreVocabulary::genresFor),
            new CatalogFilterField(
                CatalogFilterKeys.ORIGINAL_LANGUAGE, languageVocabulary::supportsOriginalLanguage, languageVocabulary::originalLanguageOptionsFor
            ),
            new CatalogFilterField(
                CatalogFilterKeys.AVAILABLE_IN_LANGUAGE,
                languageVocabulary::supportsAvailableInLanguage, languageVocabulary::availableInLanguageOptionsFor
            ),
            // Runtime and page count have no enumerable option list (see
            // CatalogFilterField's Javadoc) — mediaType -> List.of() is
            // therefore an honest "no discrete options" rather than a
            // placeholder, and CatalogRange.isValid replaces the
            // enumeration-based validator the 3-arg constructor would
            // otherwise derive from that (necessarily empty) list.
            new CatalogFilterField(
                CatalogFilterKeys.RUNTIME, RUNTIME_MEDIA_TYPES::contains, mediaType -> List.of(),
                (mediaType, value) -> CatalogRange.isValid(value)
            ),
            new CatalogFilterField(
                CatalogFilterKeys.PAGE_COUNT, PAGE_COUNT_MEDIA_TYPES::contains, mediaType -> List.of(),
                (mediaType, value) -> CatalogRange.isValid(value)
            )
        );
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
     * The sort- and filter-aware overload the Catalog controller calls.
     * While a search is active, {@code sortKey}/{@code sortDirection}/
     * {@code filters} are honored only as far as ADR 0018's "Behavior under
     * active text search" table allows: dropped entirely for Movies/TV
     * (TMDB's search endpoints accept only the query itself, so this method
     * goes straight to {@link #searchFeedFor(String, String, int)} without
     * ever resolving or persisting either), sort-only-dropped for Games
     * (IGDB's search clause can't combine with an explicit sort, but its
     * filters still apply), and fully honored for Books (OpenLibrary's
     * search endpoint already combines a query with sort and every filter).
     * Outside a search, every media type resolves and persists sort/filters
     * exactly as before.
     *
     * <p>{@code filters} is keyed by filter field (see {@link
     * CatalogFilterKeys}); each field is resolved independently by the same
     * rule an unrecognised sort key/direction already follows: a field
     * simply absent from the map (the caller didn't mention it) falls back
     * to whatever's already persisted for that field, so that a request
     * changing only one field can't silently clobber another (the read-
     * merge-write ADR 0025's store Javadoc requires, since {@link
     * SurfacePreferenceStore#upsert} replaces the whole row). A field mapped
     * to the <em>empty string</em> is different from being absent: it's the
     * frontend's explicit "no value selected" signal (a deselected filter
     * chip) and clears rather than falls back — {@code CatalogController}
     * can make this distinction because HTTP already distinguishes an
     * absent query param from a present-but-empty one. The combined result —
     * real or persisted-fallback sort, and each field's real, cleared, or
     * persisted-fallback value — is what actually gets upserted and what the
     * page is fetched with; this holds even while a media type's search
     * disables part of that combination for the *fetch*, so a locked field
     * still round-trips through persistence untouched (the frontend simply
     * never sends a changed value for a field it's rendering as locked).
     */
    public CatalogPageResult browse(
        String mediaType, String search, String sortKey, String sortDirection, Map<String, String> filters, long userId, int page
    ) {
        var query = normalizeSearch(search);
        if (query != null && !SEARCH_COMBINES_SORT_OR_FILTERS_MEDIA_TYPES.contains(mediaType)) {
            return searchFeedFor(mediaType, query, page);
        }
        if (!SUPPORTED_MEDIA_TYPES.contains(mediaType)) {
            throw new UnsupportedCatalogMediaTypeException(mediaType);
        }

        var existing = surfacePreferenceStore.get(userId, CATALOG_SURFACE, mediaType);
        var resolvedSort = resolveSort(sortKey, sortDirection, existing);
        var resolvedFilters = resolveFilters(mediaType, filters, existing);
        // "Nothing meaningful requested" (no valid sort, no filter field
        // worth resolving) is only a safe shortcut to the plain popular feed
        // once persistence has ALSO been consulted and come up empty — an
        // already-persisted preference must still be restored (ADR 0025)
        // rather than silently dropped just because this particular request
        // didn't repeat it.
        var requestedNothingMeaningful =
            !isValidSort(sortKey, sortDirection) && filterFields.stream().noneMatch(field -> isMeaningfulRequest(mediaType, field, filters));
        if (query == null && requestedNothingMeaningful && resolvedSort.key() == null && resolvedFilters.isEmpty()) {
            return popularFeedFor(mediaType, page);
        }

        var fetchSortKey = resolvedSort.key() != null ? resolvedSort.key() : CatalogSort.POPULARITY;
        var fetchSortDirection = resolvedSort.direction() != null ? resolvedSort.direction() : CatalogSort.DESCENDING;
        var sortAppliesDuringSearch = !SEARCH_DISABLES_SORT_MEDIA_TYPES.contains(mediaType);

        var result = query != null
            ? searchFilteredFeedFor(
                mediaType, query, sortAppliesDuringSearch ? fetchSortKey : null, sortAppliesDuringSearch ? fetchSortDirection : null,
                resolvedFilters, page
              )
            : filteredFeedFor(mediaType, fetchSortKey, fetchSortDirection, resolvedFilters, page);
        surfacePreferenceStore.upsert(
            userId, CATALOG_SURFACE, mediaType, resolvedSort.key(), resolvedSort.direction(), encodeFilters(resolvedFilters)
        );
        return result;
    }

    /**
     * Which sort/filter fields ADR 0018 locks right now for {@code
     * mediaType} given whether {@code search} is active — the signal
     * {@code CatalogController} folds into the browse response so {@code
     * SortControl}/{@code FilterControls} can show a "not available while
     * searching" note instead of duplicating this per-provider table
     * themselves.
     */
    public CatalogSearchCapabilities searchCapabilities(String mediaType, String search) {
        if (normalizeSearch(search) == null) {
            return CatalogSearchCapabilities.NONE_DISABLED;
        }
        return switch (mediaType) {
            case TmdbClient.MOVIES_MEDIA_TYPE, TmdbClient.TV_MEDIA_TYPE ->
                new CatalogSearchCapabilities(MOVIES_TV_FILTERS_DISABLED_WHILE_SEARCHING, true);
            case IgdbClient.GAMES_MEDIA_TYPE -> new CatalogSearchCapabilities(Set.of(), true);
            default -> CatalogSearchCapabilities.NONE_DISABLED;
        };
    }

    /** The current user's persisted Catalog sort and filter selections for this media type, if ever set (ADR 0025). */
    public CatalogPreference preference(long userId, String mediaType) {
        var stored = surfacePreferenceStore.get(userId, CATALOG_SURFACE, mediaType);
        var persisted = stored.map(SurfacePreference::filters).map(this::decodeFilters).orElse(Map.of());
        var validFilters = new LinkedHashMap<String, String>();
        for (var field : filterFields) {
            var value = persisted.get(field.key());
            if (value != null && field.isValidValue(mediaType, value)) {
                validFilters.put(field.key(), value);
            }
        }
        return new CatalogPreference(
            stored.map(SurfacePreference::sortKey).orElse(null),
            stored.map(SurfacePreference::sortDirection).orElse(null),
            Map.copyOf(validFilters)
        );
    }

    /**
     * Which filter kinds {@code FilterControls} should offer for this media
     * type right now (ADR 0018's per-type capability table), keyed by filter
     * field — a media type can report more than one (e.g. Movies: genre and
     * original language). Omits a field (rather than failing the whole
     * browse response) when its vocabulary is temporarily unreachable.
     */
    public Map<String, List<CatalogFilterOption>> availableFilters(String mediaType) {
        var result = new LinkedHashMap<String, List<CatalogFilterOption>>();
        for (var field : filterFields) {
            if (!field.supports().test(mediaType)) {
                continue;
            }
            try {
                result.put(field.key(), field.optionsFor().apply(mediaType));
            } catch (CatalogUpstreamException e) {
                LOG.warn("{} vocabulary temporarily unavailable for {}; omitting it from the capability payload", field.key(), mediaType, e);
            }
        }
        return Map.copyOf(result);
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

    private static boolean isValidSort(String sortKey, String sortDirection) {
        return sortKey != null && VALID_SORT_KEYS.contains(sortKey)
            && sortDirection != null && VALID_DIRECTIONS.contains(sortDirection);
    }

    private boolean isMeaningfulRequest(String mediaType, CatalogFilterField field, Map<String, String> requested) {
        if (!requested.containsKey(field.key())) {
            return false;
        }
        var value = requested.get(field.key());
        return "".equals(value) || field.isValidValue(mediaType, value);
    }

    // Each field is resolved independently: an explicit clear ("") drops it
    // regardless of what's persisted; a valid requested value takes it;
    // anything else (not mentioned, or an invalid value) falls back to
    // whatever's already persisted for that field, provided that value is
    // still valid for the current media type and vocabulary.
    private Map<String, String> resolveFilters(String mediaType, Map<String, String> requested, Optional<SurfacePreference> existing) {
        var persisted = existing.map(SurfacePreference::filters).map(this::decodeFilters).orElse(Map.<String, String>of());
        var resolved = new LinkedHashMap<String, String>();
        for (var field : filterFields) {
            var key = field.key();
            if (requested.containsKey(key)) {
                var requestedValue = requested.get(key);
                if ("".equals(requestedValue)) {
                    continue;
                }
                if (field.isValidValue(mediaType, requestedValue)) {
                    resolved.put(key, requestedValue);
                    continue;
                }
            }
            var persistedValue = persisted.get(key);
            if (persistedValue != null && field.isValidValue(mediaType, persistedValue)) {
                resolved.put(key, persistedValue);
            }
        }
        return resolved;
    }

    private String encodeFilters(Map<String, String> filters) {
        if (filters.isEmpty()) {
            return null;
        }
        return objectMapper.writeValueAsString(filters);
    }

    private Map<String, String> decodeFilters(String filtersJson) {
        if (filtersJson == null) {
            return Map.of();
        }
        try {
            var decoded = objectMapper.readValue(filtersJson, FILTERS_JSON_TYPE);
            return decoded != null ? decoded : Map.<String, String>of();
        } catch (JacksonException e) {
            LOG.warn("Could not parse persisted catalog filters {}; treating as none", filtersJson, e);
            return Map.of();
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

    private CatalogPageResult filteredFeedFor(String mediaType, String sortKey, String sortDirection, Map<String, String> filters, int page) {
        var genre = filters.get(CatalogFilterKeys.GENRE);
        var originalLanguage = filters.get(CatalogFilterKeys.ORIGINAL_LANGUAGE);
        var availableInLanguage = filters.get(CatalogFilterKeys.AVAILABLE_IN_LANGUAGE);
        var runtime = filters.get(CatalogFilterKeys.RUNTIME);
        var pageCount = filters.get(CatalogFilterKeys.PAGE_COUNT);
        return switch (mediaType) {
            case TmdbClient.MOVIES_MEDIA_TYPE -> filteredFeed(
                TmdbClient.PROVIDER, TmdbClient.MOVIES_MEDIA_TYPE, sortKey, sortDirection, filters,
                pageToFetch -> tmdbClient.discoverMovies(sortKey, sortDirection, genre, originalLanguage, runtime, pageToFetch), page
            );
            case TmdbClient.TV_MEDIA_TYPE -> filteredFeed(
                TmdbClient.PROVIDER, TmdbClient.TV_MEDIA_TYPE, sortKey, sortDirection, filters,
                pageToFetch -> tmdbClient.discoverTv(sortKey, sortDirection, genre, originalLanguage, runtime, pageToFetch), page
            );
            case OpenLibraryClient.BOOKS_MEDIA_TYPE -> filteredFeed(
                OpenLibraryClient.PROVIDER, OpenLibraryClient.BOOKS_MEDIA_TYPE, sortKey, sortDirection, filters,
                pageToFetch -> openLibraryClient.sortedBooks(
                    sortKey, sortDirection, booksSubjectAliases(genre), availableInLanguage, pageCount, pageToFetch
                ), page
            );
            case IgdbClient.GAMES_MEDIA_TYPE -> filteredFeed(
                IgdbClient.PROVIDER, IgdbClient.GAMES_MEDIA_TYPE, sortKey, sortDirection, filters,
                pageToFetch -> igdbClient.discoverGames(sortKey, sortDirection, genre, availableInLanguage, pageToFetch), page
            );
            default -> throw new UnsupportedCatalogMediaTypeException(mediaType);
        };
    }

    // Books' curated genre (ADR 0013) resolves to its OpenLibrary subject
    // aliases here, not inside OpenLibraryClient — the same split as TMDB/
    // IGDB, where this class resolves and validates the genre value and the
    // provider client only knows how to turn it into its own query syntax.
    private List<String> booksSubjectAliases(String genre) {
        return genre != null ? genreVocabulary.booksSubjectAliases(genre) : List.of();
    }

    // The "text search active" counterpart to filteredFeedFor, reached only
    // for Games and Books (see SEARCH_COMBINES_SORT_OR_FILTERS_MEDIA_TYPES —
    // Movies/TV never resolve this far). sortKey/sortDirection already
    // arrive null from browse() for Games, whose IgdbClient#search has no
    // sort parameter to receive them at all — a defensive backstop beyond
    // just the frontend withholding them, per ADR 0018.
    private CatalogPageResult searchFilteredFeedFor(
        String mediaType, String query, String sortKey, String sortDirection, Map<String, String> filters, int page
    ) {
        var genre = filters.get(CatalogFilterKeys.GENRE);
        var availableInLanguage = filters.get(CatalogFilterKeys.AVAILABLE_IN_LANGUAGE);
        var pageCount = filters.get(CatalogFilterKeys.PAGE_COUNT);
        var feedSlot = SEARCH_FEED_PREFIX + query;
        return switch (mediaType) {
            case IgdbClient.GAMES_MEDIA_TYPE -> filteredFeed(
                IgdbClient.PROVIDER, IgdbClient.GAMES_MEDIA_TYPE, feedSlot, sortKey, sortDirection, filters,
                pageToFetch -> igdbClient.search(query, genre, availableInLanguage, pageToFetch), page
            );
            case OpenLibraryClient.BOOKS_MEDIA_TYPE -> filteredFeed(
                OpenLibraryClient.PROVIDER, OpenLibraryClient.BOOKS_MEDIA_TYPE, feedSlot, sortKey, sortDirection, filters,
                pageToFetch -> openLibraryClient.sortedBooks(
                    query, sortKey, sortDirection, booksSubjectAliases(genre), availableInLanguage, pageCount, pageToFetch
                ), page
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

    // A "popular sorted by X, optionally filtered by Y" page is cached
    // separately from both the plain popular feed and any search — it
    // shares the "popular" feed/query slot (it's still the unfiltered-by-
    // search browse) but carries the real sort and filters in the slots
    // popularFeed always fills with DEFAULT_SORT/NO_FILTERS, so choosing a
    // sort or a filter can never collide with — or be served by — the
    // default popular page's cache entry. Filters are sorted by key before
    // joining so the same filter combination always produces the same cache
    // key regardless of the map's iteration order.
    private CatalogPageResult filteredFeed(
        String provider, String mediaType, String sortKey, String sortDirection, Map<String, String> filters,
        IntFunction<CatalogPageResult> fetchPage, int page
    ) {
        return filteredFeed(provider, mediaType, POPULAR_FEED, sortKey, sortDirection, filters, fetchPage, page);
    }

    // feedSlot is POPULAR_FEED for the plain "filter/sort applied" row and
    // SEARCH_FEED_PREFIX + query for searchFilteredFeedFor's "text search
    // active" row (ADR 0018) — either way it keeps that row's cache entries
    // from colliding with the plain popular feed's or a different search's.
    private CatalogPageResult filteredFeed(
        String provider, String mediaType, String feedSlot, String sortKey, String sortDirection, Map<String, String> filters,
        IntFunction<CatalogPageResult> fetchPage, int page
    ) {
        var filtersKeyPart = filters.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + ":" + entry.getValue())
            .collect(Collectors.joining(","));
        var key = CatalogCache.pageKey(
            provider, mediaType, feedSlot, filtersKeyPart.isEmpty() ? NO_FILTERS : filtersKeyPart, sortKey + ":" + sortDirection, page
        );
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
