package dev.yzlaboratory.alexandrea.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

/**
 * Talks to TMDB's {@code /movie/popular}/{@code /tv/popular}, {@code
 * /discover/movie}/{@code /discover/tv}, and {@code /search/movie}/{@code
 * /search/tv} endpoints (ADR 0018's "nothing applied", "filter/sort
 * applied", and "text search active" rows) and maps each response into the
 * common {@link CatalogItem} shape (ADR 0001). TMDB already paginates at 20
 * results per page, so the page-number contract this app exposes to the
 * frontend needs no offset math for this provider.
 *
 * <p>{@code /tv/popular} already returns one row per series — TMDB has no
 * per-season entry in this feed — so {@link #popularTv(int)} needs no extra
 * work to satisfy ADR 0005's series-level requirement beyond calling the
 * right endpoint.
 */
@Component
public class TmdbClient {

    private static final Logger LOG = LoggerFactory.getLogger(TmdbClient.class);

    // Package-private (not private): CatalogService references these
    // directly for its dispatch and cache-key building rather than
    // redeclaring its own copy that could silently drift out of sync.
    static final String PROVIDER = "TMDB";
    static final String MOVIES_MEDIA_TYPE = "movies";
    static final String TV_MEDIA_TYPE = "tv";
    private static final double TMDB_RATING_SCALE = 10.0;
    private static final String API_KEY_PARAM = "api_key";
    private static final String PAGE_PARAM = "page";
    private static final String QUERY_PARAM = "query";
    private static final String SORT_BY_PARAM = "sort_by";
    private static final String WITH_GENRES_PARAM = "with_genres";
    private static final String WITH_ORIGINAL_LANGUAGE_PARAM = "with_original_language";
    private static final String WITH_RUNTIME_GTE_PARAM = "with_runtime.gte";
    private static final String WITH_RUNTIME_LTE_PARAM = "with_runtime.lte";

    private final RestClient restClient;
    private final CatalogProperties properties;

    public TmdbClient(RestClient tmdbRestClient, CatalogProperties properties) {
        this.restClient = tmdbRestClient;
        this.properties = properties;
    }

    public CatalogPageResult popularMovies(int page) {
        return fetchPage("/movie/popular", MOVIES_MEDIA_TYPE, page, UnaryOperator.identity());
    }

    /** TMDB's native Movies genre enum (ADR 0013), for {@link GenreVocabulary} to cache. */
    public List<CatalogFilterOption> movieGenres() {
        return genreList("/genre/movie/list");
    }

    /** TMDB's native TV genre enum (ADR 0013), for {@link GenreVocabulary} to cache. */
    public List<CatalogFilterOption> tvGenres() {
        return genreList("/genre/tv/list");
    }

    public CatalogPageResult popularTv(int page) {
        return fetchPage("/tv/popular", TV_MEDIA_TYPE, page, UnaryOperator.identity());
    }

    // TMDB has no query filter on /discover — search is its own endpoint
    // pair, per ADR 0018's "text search active" row.
    public CatalogPageResult searchMovies(String query, int page) {
        return fetchPage("/search/movie", MOVIES_MEDIA_TYPE, page, uriBuilder -> uriBuilder.queryParam(QUERY_PARAM, query));
    }

    public CatalogPageResult searchTv(String query, int page) {
        return fetchPage("/search/tv", TV_MEDIA_TYPE, page, uriBuilder -> uriBuilder.queryParam(QUERY_PARAM, query));
    }

    // ADR 0018's "filter/sort applied" row: /discover/* replaces /*/popular
    // once a sort or a filter is chosen, since /movie/popular and
    // /tv/popular accept neither a sort_by, a with_genres, a
    // with_original_language, nor a with_runtime param of their own. genre
    // is TMDB's native genre id (ADR 0013's "native enum" for Movies/TV);
    // originalLanguage is an ISO 639-1 code (ADR 0018's original-language
    // filter, Movies/TV only); runtime is the "<min>,<max>" encoding
    // CatalogRange parses (ADR 0018's runtime filter, Movies/TV only) — each
    // is omitted from the request entirely when null/unparseable.
    public CatalogPageResult discoverMovies(
        String sortKey, String direction, String genre, String originalLanguage, String runtime, int page
    ) {
        return fetchPage("/discover/movie", MOVIES_MEDIA_TYPE, page, withFilters(genre, originalLanguage, runtime,
            uriBuilder -> uriBuilder.queryParam(SORT_BY_PARAM, sortByValue(sortKey, direction))));
    }

    public CatalogPageResult discoverTv(
        String sortKey, String direction, String genre, String originalLanguage, String runtime, int page
    ) {
        return fetchPage("/discover/tv", TV_MEDIA_TYPE, page, withFilters(genre, originalLanguage, runtime,
            uriBuilder -> uriBuilder.queryParam(SORT_BY_PARAM, sortByValue(sortKey, direction))));
    }

    private static UnaryOperator<UriBuilder> withFilters(
        String genre, String originalLanguage, String runtime, UnaryOperator<UriBuilder> sortParam
    ) {
        return uriBuilder -> {
            var withSort = sortParam.apply(uriBuilder);
            var withGenre = genre != null ? withSort.queryParam(WITH_GENRES_PARAM, genre) : withSort;
            var withLanguage = originalLanguage != null ? withGenre.queryParam(WITH_ORIGINAL_LANGUAGE_PARAM, originalLanguage) : withGenre;
            return withRuntime(withLanguage, runtime);
        };
    }

    // with_runtime.gte and .lte are independent params, so an open-ended
    // CatalogRange (only one bound set) sends only that one param rather
    // than inventing a sentinel value for the missing side; an unparseable
    // or absent runtime value adds neither.
    private static UriBuilder withRuntime(UriBuilder uriBuilder, String runtime) {
        var range = CatalogRange.parse(runtime);
        if (range.isEmpty()) {
            return uriBuilder;
        }
        var min = range.get().min();
        var max = range.get().max();
        var withMin = min != null ? uriBuilder.queryParam(WITH_RUNTIME_GTE_PARAM, min) : uriBuilder;
        return max != null ? withMin.queryParam(WITH_RUNTIME_LTE_PARAM, max) : withMin;
    }

    // TMDB's sort_by accepts "<field>.<asc|desc>" for every field below in
    // either direction (ADR 0018 pins only the default direction's literal
    // value, e.g. "popularity.desc" — the opposite direction is the same
    // mechanism with the suffix flipped).
    private static String sortByValue(String sortKey, String direction) {
        var field = switch (sortKey) {
            case CatalogSort.POPULARITY -> "popularity";
            case CatalogSort.RELEASE_DATE -> "primary_release_date";
            case CatalogSort.TITLE -> "original_title";
            case CatalogSort.EXTERNAL_RATING -> "vote_average";
            default -> throw new IllegalArgumentException("Unsupported sort key: " + sortKey);
        };
        return field + "." + direction;
    }

    // Shared by the popular feed and search: TMDB returns the identical
    // {page, results, total_pages} shape for both, so the same page/hasMore
    // math and CatalogItem mapping apply either way — only the path and
    // (for search) the extra "query" param differ.
    private CatalogPageResult fetchPage(String path, String mediaType, int page, UnaryOperator<UriBuilder> extraParams) {
        var response = fetchResponse(path, page, extraParams);
        // response itself is never null (fetchResponse falls back to
        // TmdbPopularResponse.empty(page)), but a 200 whose body omits
        // "results" (or sends it explicitly null) deserializes the field to
        // null too — guard here rather than NPE outside fetchResponse's
        // try/catch, which would surface as a bare 500 instead of the
        // intended CatalogUpstreamException -> 503.
        var results = response.results() != null ? response.results() : List.<TmdbTitle>of();
        var items = results.stream().map(result -> toItem(result, mediaType)).toList();
        var hasMore = page < response.totalPages();
        return new CatalogPageResult(items, page, hasMore);
    }

    private TmdbPopularResponse fetchResponse(String path, int page, UnaryOperator<UriBuilder> extraParams) {
        try {
            var response = restClient.get()
                .uri(uriBuilder -> extraParams.apply(
                        uriBuilder.path(path)
                            .queryParam(API_KEY_PARAM, properties.tmdb().apiKey())
                            .queryParam(PAGE_PARAM, page))
                    .build())
                .retrieve()
                .body(TmdbPopularResponse.class);
            return response != null ? response : TmdbPopularResponse.empty(page);
        } catch (RestClientException e) {
            throw new CatalogUpstreamException(PROVIDER, e);
        }
    }

    // TMDB names a movie's title field "title"/"release_date" and a series'
    // "name"/"first_air_date" — one shared record with both pairs, picking
    // whichever this mediaType's endpoint actually populated, avoids a
    // second near-identical response/mapping pair for TV.
    private CatalogItem toItem(TmdbTitle result, String mediaType) {
        return new CatalogItem(
            PROVIDER,
            String.valueOf(result.id()),
            mediaType,
            result.title() != null ? result.title() : result.name(),
            coverUrl(result.posterPath()),
            parseReleaseDate(result.releaseDate() != null ? result.releaseDate() : result.firstAirDate()),
            result.voteAverage(),
            TMDB_RATING_SCALE
        );
    }

    private List<CatalogFilterOption> genreList(String path) {
        try {
            var response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path(path).queryParam(API_KEY_PARAM, properties.tmdb().apiKey()).build())
                .retrieve()
                .body(TmdbGenreListResponse.class);
            var genres = response != null && response.genres() != null ? response.genres() : List.<TmdbGenre>of();
            return genres.stream().map(genre -> new CatalogFilterOption(String.valueOf(genre.id()), genre.name())).toList();
        } catch (RestClientException e) {
            throw new CatalogUpstreamException(PROVIDER, e);
        }
    }

    private String coverUrl(String posterPath) {
        if (posterPath == null || posterPath.isBlank()) {
            return null;
        }
        return properties.tmdb().imageBaseUrl() + posterPath;
    }

    private static LocalDate parseReleaseDate(String releaseDate) {
        if (releaseDate == null || releaseDate.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(releaseDate);
        } catch (DateTimeParseException e) {
            // TMDB is not always consistent about this field's format on
            // unreleased/announced titles; treat it the same as "unknown"
            // rather than failing the whole page over one bad date. Logged
            // (not silently dropped) so a systematic upstream format
            // regression is visible in Loki instead of only ever showing up
            // as an unexplained rise in null release dates.
            LOG.warn("TMDB release_date {} did not parse as ISO-8601; treating as unknown", releaseDate);
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TmdbPopularResponse(
        int page, List<TmdbTitle> results, @JsonProperty("total_pages") int totalPages
    ) {
        static TmdbPopularResponse empty(int page) {
            return new TmdbPopularResponse(page, List.of(), page);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TmdbTitle(
        long id,
        String title,
        String name,
        @JsonProperty("poster_path") String posterPath,
        @JsonProperty("release_date") String releaseDate,
        @JsonProperty("first_air_date") String firstAirDate,
        @JsonProperty("vote_average") Double voteAverage
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TmdbGenreListResponse(List<TmdbGenre> genres) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TmdbGenre(long id, String name) {}
}
