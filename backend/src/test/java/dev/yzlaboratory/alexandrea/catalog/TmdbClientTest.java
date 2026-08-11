package dev.yzlaboratory.alexandrea.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Request shape and response mapping for TMDB's {@code /movie/popular}
 * endpoint, against captured/mocked HTTP responses rather than a live call
 * (per the feature ticket's testing decisions).
 */
class TmdbClientTest {

    private static final String BASE_URL = "https://api.themoviedb.org/3";

    private MockRestServiceServer server;
    private TmdbClient client;

    @BeforeEach
    void setUp() {
        var properties = new CatalogProperties(
            new CatalogProperties.Tmdb(BASE_URL, "test-key", "https://image.tmdb.org/t/p/w500"), null, null);
        var builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TmdbClient(builder.build(), properties);
    }

    @Test
    void mapsAPopularMoviesResponseIntoCatalogItems() {
        expectPopularRequest().andRespond(withSuccess("""
            {
              "page": 1,
              "results": [
                {
                  "id": 603692,
                  "title": "John Wick: Chapter 4",
                  "poster_path": "/vZloFAK7NmvMGKE7VkF5UHaz0I.jpg",
                  "release_date": "2023-03-22",
                  "vote_average": 7.8
                }
              ],
              "total_pages": 500
            }
            """, MediaType.APPLICATION_JSON));

        var result = client.popularMovies(1);

        assertThat(result.items()).hasSize(1);
        var item = result.items().getFirst();
        assertThat(item.provider()).isEqualTo("TMDB");
        assertThat(item.externalId()).isEqualTo("603692");
        assertThat(item.mediaType()).isEqualTo("movies");
        assertThat(item.title()).isEqualTo("John Wick: Chapter 4");
        assertThat(item.coverUrl())
            .isEqualTo("https://image.tmdb.org/t/p/w500/vZloFAK7NmvMGKE7VkF5UHaz0I.jpg");
        assertThat(item.releaseDate()).isEqualTo(LocalDate.of(2023, 3, 22));
        assertThat(item.externalRating()).isEqualTo(7.8);
        assertThat(item.externalRatingScale()).isEqualTo(10.0);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.hasMore()).isTrue();
    }

    @Test
    void aLastPageReportsNoMorePagesAvailable() {
        server.expect(requestTo(startsWith(BASE_URL + "/movie/popular")))
            .andExpect(queryParam("page", "500"))
            .andRespond(withSuccess("""
                {"page": 500, "results": [], "total_pages": 500}
                """, MediaType.APPLICATION_JSON));

        var result = client.popularMovies(500);

        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void aMovieWithNoPosterOrReleaseDateMapsToNullFieldsRatherThanCrashing() {
        expectPopularRequest().andRespond(withSuccess("""
            {
              "page": 1,
              "results": [
                {"id": 1, "title": "Untitled Upcoming Film", "poster_path": null, "release_date": "", "vote_average": 0.0}
              ],
              "total_pages": 1
            }
            """, MediaType.APPLICATION_JSON));

        var item = client.popularMovies(1).items().getFirst();

        assertThat(item.coverUrl()).isNull();
        assertThat(item.releaseDate()).isNull();
        // TMDB reports 0.0 (not null) for an unrated title — a real, present
        // rating of zero, distinct from "no rating field at all".
        assertThat(item.externalRating()).isEqualTo(0.0);
    }

    @Test
    void aMalformedReleaseDateMapsToNullRatherThanThrowing() {
        expectPopularRequest().andRespond(withSuccess("""
            {
              "page": 1,
              "results": [
                {"id": 1, "title": "Odd Date", "poster_path": null, "release_date": "not-a-date", "vote_average": 5.0}
              ],
              "total_pages": 1
            }
            """, MediaType.APPLICATION_JSON));

        var item = client.popularMovies(1).items().getFirst();

        assertThat(item.releaseDate()).isNull();
    }

    @Test
    void anEmptyResultsPageMapsToAnEmptyListWithoutError() {
        server.expect(requestTo(startsWith(BASE_URL + "/movie/popular")))
            .andExpect(queryParam("page", "50"))
            .andRespond(withSuccess("""
                {"page": 50, "results": [], "total_pages": 10}
                """, MediaType.APPLICATION_JSON));

        var result = client.popularMovies(50);

        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void aNullResultsFieldMapsToAnEmptyListRatherThanThrowing() {
        server.expect(requestTo(startsWith(BASE_URL + "/movie/popular")))
            .andExpect(queryParam("page", "60"))
            .andRespond(withSuccess("""
                {"page": 60, "results": null, "total_pages": 60}
                """, MediaType.APPLICATION_JSON));

        var result = client.popularMovies(60);

        assertThat(result.items()).isEmpty();
    }

    @Test
    void anUpstream5xxIsWrappedAsACatalogUpstreamException() {
        expectPopularRequest().andRespond(withServerError());

        assertThatThrownBy(() -> client.popularMovies(1)).isInstanceOf(CatalogUpstreamException.class);
    }

    @Test
    void requestsTheGivenPageNumberAndApiKeyAsQueryParams() {
        server.expect(requestTo(startsWith(BASE_URL + "/movie/popular")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("page", "7"))
            .andExpect(queryParam("api_key", "test-key"))
            .andRespond(withSuccess("""
                {"page": 7, "results": [], "total_pages": 10}
                """, MediaType.APPLICATION_JSON));

        client.popularMovies(7);

        server.verify();
    }

    private org.springframework.test.web.client.ResponseActions expectPopularRequest() {
        return server.expect(requestTo(startsWith(BASE_URL + "/movie/popular")))
            .andExpect(method(HttpMethod.GET));
    }

    @Test
    void mapsAPopularTvResponseIntoOneSeriesLevelCatalogItemPerEntry() {
        server.expect(requestTo(startsWith(BASE_URL + "/tv/popular")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""
                {
                  "page": 1,
                  "results": [
                    {
                      "id": 66732,
                      "name": "Stranger Things",
                      "poster_path": "/x2LSRK2Cm7MZhjluni1msVJ3wDF.jpg",
                      "first_air_date": "2016-07-15",
                      "vote_average": 8.6
                    }
                  ],
                  "total_pages": 500
                }
                """, MediaType.APPLICATION_JSON));

        var result = client.popularTv(1);

        // One CatalogItem per series entry TMDB returned — /tv/popular
        // already lists whole series (never a season or episode row), so
        // ADR 0005's series-level requirement holds without any extra
        // de-duplication or per-season filtering here.
        assertThat(result.items()).hasSize(1);
        var item = result.items().getFirst();
        assertThat(item.provider()).isEqualTo("TMDB");
        assertThat(item.externalId()).isEqualTo("66732");
        assertThat(item.mediaType()).isEqualTo("tv");
        assertThat(item.title()).isEqualTo("Stranger Things");
        assertThat(item.coverUrl())
            .isEqualTo("https://image.tmdb.org/t/p/w500/x2LSRK2Cm7MZhjluni1msVJ3wDF.jpg");
        assertThat(item.releaseDate()).isEqualTo(LocalDate.of(2016, 7, 15));
        assertThat(item.externalRating()).isEqualTo(8.6);
        assertThat(item.externalRatingScale()).isEqualTo(10.0);
    }

    @Test
    void requestsTvFromTheTvPopularPathNotTheMoviesPath() {
        server.expect(requestTo(startsWith(BASE_URL + "/tv/popular")))
            .andExpect(queryParam("page", "3"))
            .andExpect(queryParam("api_key", "test-key"))
            .andRespond(withSuccess("""
                {"page": 3, "results": [], "total_pages": 10}
                """, MediaType.APPLICATION_JSON));

        client.popularTv(3);

        server.verify();
    }

    @Test
    void aTvSeriesWithNoPosterOrAirDateMapsToNullFieldsRatherThanCrashing() {
        server.expect(requestTo(startsWith(BASE_URL + "/tv/popular")))
            .andRespond(withSuccess("""
                {
                  "page": 1,
                  "results": [
                    {"id": 2, "name": "Untitled Upcoming Series", "poster_path": null, "first_air_date": "", "vote_average": 0.0}
                  ],
                  "total_pages": 1
                }
                """, MediaType.APPLICATION_JSON));

        var item = client.popularTv(1).items().getFirst();

        assertThat(item.coverUrl()).isNull();
        assertThat(item.releaseDate()).isNull();
        assertThat(item.externalRating()).isEqualTo(0.0);
    }

    @Test
    void anUpstream5xxOnTvIsWrappedAsACatalogUpstreamException() {
        server.expect(requestTo(startsWith(BASE_URL + "/tv/popular"))).andRespond(withServerError());

        assertThatThrownBy(() -> client.popularTv(1)).isInstanceOf(CatalogUpstreamException.class);
    }

    @Test
    void searchMoviesMapsAMatchingResponseIntoCatalogItemsJustLikePopular() {
        server.expect(requestTo(startsWith(BASE_URL + "/search/movie")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("query", "blade%20runner"))
            .andExpect(queryParam("api_key", "test-key"))
            .andRespond(withSuccess("""
                {
                  "page": 1,
                  "results": [
                    {
                      "id": 78,
                      "title": "Blade Runner",
                      "poster_path": "/blade.jpg",
                      "release_date": "1982-06-25",
                      "vote_average": 7.9
                    }
                  ],
                  "total_pages": 1
                }
                """, MediaType.APPLICATION_JSON));

        var result = client.searchMovies("blade runner", 1);

        assertThat(result.items()).hasSize(1);
        var item = result.items().getFirst();
        assertThat(item.provider()).isEqualTo("TMDB");
        assertThat(item.externalId()).isEqualTo("78");
        assertThat(item.mediaType()).isEqualTo("movies");
        assertThat(item.title()).isEqualTo("Blade Runner");
        assertThat(item.releaseDate()).isEqualTo(LocalDate.of(1982, 6, 25));
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void searchMoviesWithNoMatchesMapsToAnEmptyPageRatherThanAnError() {
        server.expect(requestTo(startsWith(BASE_URL + "/search/movie")))
            .andExpect(queryParam("query", "zzzznomatch"))
            .andRespond(withSuccess("""
                {"page": 1, "results": [], "total_pages": 1}
                """, MediaType.APPLICATION_JSON));

        var result = client.searchMovies("zzzznomatch", 1);

        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void searchMoviesRequestsTheGivenPageNumberAsAQueryParam() {
        server.expect(requestTo(startsWith(BASE_URL + "/search/movie")))
            .andExpect(queryParam("query", "sequel"))
            .andExpect(queryParam("page", "3"))
            .andRespond(withSuccess("""
                {"page": 3, "results": [], "total_pages": 5}
                """, MediaType.APPLICATION_JSON));

        var result = client.searchMovies("sequel", 3);

        assertThat(result.hasMore()).isTrue();
    }

    @Test
    void anUpstream5xxOnMovieSearchIsWrappedAsACatalogUpstreamException() {
        server.expect(requestTo(startsWith(BASE_URL + "/search/movie"))).andRespond(withServerError());

        assertThatThrownBy(() -> client.searchMovies("blade runner", 1)).isInstanceOf(CatalogUpstreamException.class);
    }

    @Test
    void searchTvRequestsTheTvSearchPathNotTheMoviesSearchPath() {
        server.expect(requestTo(startsWith(BASE_URL + "/search/tv")))
            .andExpect(queryParam("query", "stranger%20things"))
            .andRespond(withSuccess("""
                {
                  "page": 1,
                  "results": [
                    {"id": 66732, "name": "Stranger Things", "poster_path": "/st.jpg", "first_air_date": "2016-07-15", "vote_average": 8.6}
                  ],
                  "total_pages": 1
                }
                """, MediaType.APPLICATION_JSON));

        var result = client.searchTv("stranger things", 1);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().mediaType()).isEqualTo("tv");
        assertThat(result.items().getFirst().title()).isEqualTo("Stranger Things");
    }

    @Test
    void anUpstream5xxOnTvSearchIsWrappedAsACatalogUpstreamException() {
        server.expect(requestTo(startsWith(BASE_URL + "/search/tv"))).andRespond(withServerError());

        assertThatThrownBy(() -> client.searchTv("stranger things", 1)).isInstanceOf(CatalogUpstreamException.class);
    }

    @Test
    void discoverMoviesRequestsThePopularityDescendingSortByValueFromAdr0018() {
        server.expect(requestTo(startsWith(BASE_URL + "/discover/movie")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("sort_by", "popularity.desc"))
            .andRespond(withSuccess("""
                {"page": 1, "results": [], "total_pages": 1}
                """, MediaType.APPLICATION_JSON));

        client.discoverMovies("popularity", "desc", 1);

        server.verify();
    }

    @Test
    void discoverMoviesMapsReleaseDateSortToPrimaryReleaseDateDescending() {
        assertDiscoverMoviesSortBy("release_date", "desc", "primary_release_date.desc");
    }

    @Test
    void discoverMoviesMapsTitleSortToOriginalTitleAscending() {
        assertDiscoverMoviesSortBy("title", "asc", "original_title.asc");
    }

    @Test
    void discoverMoviesMapsExternalRatingSortToVoteAverageDescending() {
        assertDiscoverMoviesSortBy("external_rating", "desc", "vote_average.desc");
    }

    @Test
    void discoverMoviesHonorsAscendingPopularityAsTheOppositeOfTheAdr0018Default() {
        assertDiscoverMoviesSortBy("popularity", "asc", "popularity.asc");
    }

    @Test
    void discoverMoviesHonorsAscendingReleaseDateAsTheOppositeOfTheAdr0018Default() {
        assertDiscoverMoviesSortBy("release_date", "asc", "primary_release_date.asc");
    }

    @Test
    void discoverMoviesHonorsDescendingTitleAsTheOppositeOfTheAdr0018Default() {
        assertDiscoverMoviesSortBy("title", "desc", "original_title.desc");
    }

    @Test
    void discoverMoviesHonorsAscendingExternalRatingAsTheOppositeOfTheAdr0018Default() {
        assertDiscoverMoviesSortBy("external_rating", "asc", "vote_average.asc");
    }

    @Test
    void discoverMoviesMapsAResponseIntoCatalogItemsJustLikePopular() {
        server.expect(requestTo(startsWith(BASE_URL + "/discover/movie")))
            .andRespond(withSuccess("""
                {
                  "page": 1,
                  "results": [
                    {"id": 603692, "title": "John Wick: Chapter 4", "poster_path": "/x.jpg", "release_date": "2023-03-22", "vote_average": 7.8}
                  ],
                  "total_pages": 1
                }
                """, MediaType.APPLICATION_JSON));

        var result = client.discoverMovies("popularity", "desc", 1);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().title()).isEqualTo("John Wick: Chapter 4");
    }

    @Test
    void anUpstream5xxOnDiscoverMoviesIsWrappedAsACatalogUpstreamException() {
        server.expect(requestTo(startsWith(BASE_URL + "/discover/movie"))).andRespond(withServerError());

        assertThatThrownBy(() -> client.discoverMovies("popularity", "desc", 1))
            .isInstanceOf(CatalogUpstreamException.class);
    }

    @Test
    void discoverTvRequestsTheDiscoverTvPathNotDiscoverMovie() {
        server.expect(requestTo(startsWith(BASE_URL + "/discover/tv")))
            .andExpect(queryParam("sort_by", "vote_average.desc"))
            .andRespond(withSuccess("""
                {
                  "page": 1,
                  "results": [
                    {"id": 66732, "name": "Stranger Things", "poster_path": "/st.jpg", "first_air_date": "2016-07-15", "vote_average": 8.6}
                  ],
                  "total_pages": 1
                }
                """, MediaType.APPLICATION_JSON));

        var result = client.discoverTv("external_rating", "desc", 1);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().mediaType()).isEqualTo("tv");
        assertThat(result.items().getFirst().title()).isEqualTo("Stranger Things");
    }

    @Test
    void anUpstream5xxOnDiscoverTvIsWrappedAsACatalogUpstreamException() {
        server.expect(requestTo(startsWith(BASE_URL + "/discover/tv"))).andRespond(withServerError());

        assertThatThrownBy(() -> client.discoverTv("popularity", "desc", 1))
            .isInstanceOf(CatalogUpstreamException.class);
    }

    @Test
    void movieGenresMapsTheNativeTmdbEnumIntoCatalogFilterOptions() {
        server.expect(requestTo(startsWith(BASE_URL + "/genre/movie/list")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("api_key", "test-key"))
            .andRespond(withSuccess("""
                {"genres": [{"id": 28, "name": "Action"}, {"id": 35, "name": "Comedy"}]}
                """, MediaType.APPLICATION_JSON));

        var genres = client.movieGenres();

        assertThat(genres).containsExactly(
            new CatalogFilterOption("28", "Action"),
            new CatalogFilterOption("35", "Comedy"));
    }

    @Test
    void aNullGenresFieldOnMovieGenresMapsToAnEmptyListRatherThanThrowing() {
        server.expect(requestTo(startsWith(BASE_URL + "/genre/movie/list")))
            .andRespond(withSuccess("""
                {"genres": null}
                """, MediaType.APPLICATION_JSON));

        assertThat(client.movieGenres()).isEmpty();
    }

    @Test
    void anUpstream5xxOnMovieGenresIsWrappedAsACatalogUpstreamException() {
        server.expect(requestTo(startsWith(BASE_URL + "/genre/movie/list"))).andRespond(withServerError());

        assertThatThrownBy(client::movieGenres).isInstanceOf(CatalogUpstreamException.class);
    }

    @Test
    void tvGenresRequestsTheTvGenreListPathNotTheMoviesOne() {
        server.expect(requestTo(startsWith(BASE_URL + "/genre/tv/list")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""
                {"genres": [{"id": 10759, "name": "Action & Adventure"}]}
                """, MediaType.APPLICATION_JSON));

        var genres = client.tvGenres();

        assertThat(genres).containsExactly(new CatalogFilterOption("10759", "Action & Adventure"));
    }

    @Test
    void anUpstream5xxOnTvGenresIsWrappedAsACatalogUpstreamException() {
        server.expect(requestTo(startsWith(BASE_URL + "/genre/tv/list"))).andRespond(withServerError());

        assertThatThrownBy(client::tvGenres).isInstanceOf(CatalogUpstreamException.class);
    }

    private void assertDiscoverMoviesSortBy(String sortKey, String direction, String expectedSortBy) {
        server.expect(requestTo(startsWith(BASE_URL + "/discover/movie")))
            .andExpect(queryParam("sort_by", expectedSortBy))
            .andRespond(withSuccess("""
                {"page": 1, "results": [], "total_pages": 1}
                """, MediaType.APPLICATION_JSON));

        client.discoverMovies(sortKey, direction, 1);
    }
}
