package dev.yzlaboratory.alexandrea.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Talks to TMDB's {@code /movie/popular} endpoint and maps its response into
 * the common {@link CatalogEntry} shape (ADR 0001). TMDB already paginates
 * at 20 results per page, so the page-number contract this app exposes to
 * the frontend needs no offset math for this provider.
 */
@Component
public class TmdbClient {

    private static final Logger LOG = LoggerFactory.getLogger(TmdbClient.class);

    private static final String PROVIDER = "TMDB";
    private static final String MOVIES_MEDIA_TYPE = "movies";
    private static final double TMDB_RATING_SCALE = 10.0;
    private static final String API_KEY_PARAM = "api_key";
    private static final String PAGE_PARAM = "page";

    private final RestClient restClient;
    private final CatalogProperties properties;

    public TmdbClient(RestClient tmdbRestClient, CatalogProperties properties) {
        this.restClient = tmdbRestClient;
        this.properties = properties;
    }

    public CatalogPageResult popularMovies(int page) {
        var response = fetchPopular(page);
        // response itself is never null (fetchPopular falls back to
        // TmdbPopularMoviesResponse.empty(page)), but a 200 whose body omits
        // "results" (or sends it explicitly null) deserializes the field to
        // null too — guard here rather than NPE outside fetchPopular's
        // try/catch, which would surface as a bare 500 instead of the
        // intended CatalogUpstreamException -> 503.
        var results = response.results() != null ? response.results() : List.<TmdbMovie>of();
        var entries = results.stream().map(this::toEntry).toList();
        var hasMore = page < response.totalPages();
        return new CatalogPageResult(entries, page, hasMore);
    }

    private TmdbPopularMoviesResponse fetchPopular(int page) {
        try {
            var response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/movie/popular")
                    .queryParam(API_KEY_PARAM, properties.tmdb().apiKey())
                    .queryParam(PAGE_PARAM, page)
                    .build())
                .retrieve()
                .body(TmdbPopularMoviesResponse.class);
            return response != null ? response : TmdbPopularMoviesResponse.empty(page);
        } catch (RestClientException e) {
            throw new CatalogUpstreamException(PROVIDER, e);
        }
    }

    private CatalogEntry toEntry(TmdbMovie movie) {
        return new CatalogEntry(
            PROVIDER,
            String.valueOf(movie.id()),
            MOVIES_MEDIA_TYPE,
            movie.title(),
            coverUrl(movie.posterPath()),
            parseReleaseDate(movie.releaseDate()),
            movie.voteAverage(),
            TMDB_RATING_SCALE
        );
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
    private record TmdbPopularMoviesResponse(
        int page, List<TmdbMovie> results, @JsonProperty("total_pages") int totalPages
    ) {
        static TmdbPopularMoviesResponse empty(int page) {
            return new TmdbPopularMoviesResponse(page, List.of(), page);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TmdbMovie(
        long id,
        String title,
        @JsonProperty("poster_path") String posterPath,
        @JsonProperty("release_date") String releaseDate,
        @JsonProperty("vote_average") Double voteAverage
    ) {}
}
