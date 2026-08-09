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
            new CatalogProperties.Tmdb(BASE_URL, "test-key", "https://image.tmdb.org/t/p/w500"));
        var builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TmdbClient(builder.build(), properties);
    }

    @Test
    void mapsAPopularMoviesResponseIntoCatalogEntries() {
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

        assertThat(result.entries()).hasSize(1);
        var entry = result.entries().getFirst();
        assertThat(entry.provider()).isEqualTo("TMDB");
        assertThat(entry.externalId()).isEqualTo("603692");
        assertThat(entry.mediaType()).isEqualTo("movies");
        assertThat(entry.title()).isEqualTo("John Wick: Chapter 4");
        assertThat(entry.coverUrl())
            .isEqualTo("https://image.tmdb.org/t/p/w500/vZloFAK7NmvMGKE7VkF5UHaz0I.jpg");
        assertThat(entry.releaseDate()).isEqualTo(LocalDate.of(2023, 3, 22));
        assertThat(entry.externalRating()).isEqualTo(7.8);
        assertThat(entry.externalRatingScale()).isEqualTo(10.0);
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

        var entry = client.popularMovies(1).entries().getFirst();

        assertThat(entry.coverUrl()).isNull();
        assertThat(entry.releaseDate()).isNull();
        // TMDB reports 0.0 (not null) for an unrated title — a real, present
        // rating of zero, distinct from "no rating field at all".
        assertThat(entry.externalRating()).isEqualTo(0.0);
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

        var entry = client.popularMovies(1).entries().getFirst();

        assertThat(entry.releaseDate()).isNull();
    }

    @Test
    void anEmptyResultsPageMapsToAnEmptyListWithoutError() {
        server.expect(requestTo(startsWith(BASE_URL + "/movie/popular")))
            .andExpect(queryParam("page", "50"))
            .andRespond(withSuccess("""
                {"page": 50, "results": [], "total_pages": 10}
                """, MediaType.APPLICATION_JSON));

        var result = client.popularMovies(50);

        assertThat(result.entries()).isEmpty();
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

        assertThat(result.entries()).isEmpty();
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
}
