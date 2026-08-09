package dev.yzlaboratory.alexandrea.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import dev.yzlaboratory.alexandrea.auth.AuthenticatedUser;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The observable HTTP contract of {@code GET /api/catalog/{media_type}},
 * end-to-end through the real wiring (TmdbClient, CatalogCache,
 * CatalogService) — mirrors {@code AuthEndpointTest}'s shape. TMDB itself is
 * stood in by a throwaway JDK {@link HttpServer} on a random local port,
 * wired in via {@code alexandrea.catalog.tmdb.base-url}: the same "swap the
 * outer boundary via a dynamic property, keep everything else real"
 * approach that test already uses for its SQLite datasource.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CatalogEndpointTest {

    private static Path dbFile;
    private static HttpServer tmdbServer;
    private static final AtomicReference<String> nextResponseBody = new AtomicReference<>();
    private static final AtomicReference<Integer> nextResponseStatus = new AtomicReference<>(200);
    private static final AtomicInteger requestCount = new AtomicInteger();
    private static final AtomicReference<String> lastQuery = new AtomicReference<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        // One temp DB shared across the pool's connections, same rationale as
        // AuthEndpointTest's identical setup.
        dbFile = Files.createTempFile("alexandrea-catalog-endpoint-test-", ".db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbFile + "?foreign_keys=on");
        registry.add("spring.flyway.enabled", () -> "true");

        tmdbServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        tmdbServer.createContext("/movie/popular", exchange -> {
            requestCount.incrementAndGet();
            lastQuery.set(exchange.getRequestURI().getQuery());
            var body = nextResponseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(nextResponseStatus.get(), body.length);
            try (var responseBody = exchange.getResponseBody()) {
                responseBody.write(body);
            }
        });
        tmdbServer.start();
        var port = tmdbServer.getAddress().getPort();
        registry.add("alexandrea.catalog.tmdb.base-url", () -> "http://localhost:" + port);
        registry.add("alexandrea.catalog.tmdb.api-key", () -> "test-key");
    }

    @AfterAll
    static void stopTmdbServer() {
        tmdbServer.stop(0);
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void resetState() {
        requestCount.set(0);
        lastQuery.set(null);
        nextResponseStatus.set(200);
        nextResponseBody.set("""
            {"page": 1, "results": [], "total_pages": 1}
            """);
    }

    // CatalogCache is a singleton bean shared by every test method in this
    // class (one Spring context, cached across the whole class). Each test
    // that reaches the cache uses a page number no other test uses, so a
    // prior test's cached page can never masquerade as this test's response.

    @Test
    void returnsAPageOfPopularMoviesMappedFromTmdb() throws Exception {
        nextResponseBody.set("""
            {
              "page": 11,
              "results": [
                {"id": 1, "title": "A Movie", "poster_path": "/a.jpg", "release_date": "2024-05-01", "vote_average": 8.1}
              ],
              "total_pages": 20
            }
            """);

        mockMvc.perform(get("/api/catalog/movies").param("page", "11").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].title").value("A Movie"))
            .andExpect(jsonPath("$.items[0].provider").value("TMDB"))
            .andExpect(jsonPath("$.items[0].externalId").value("1"))
            .andExpect(jsonPath("$.items[0].coverUrl").value("https://image.tmdb.org/t/p/w500/a.jpg"))
            .andExpect(jsonPath("$.items[0].releaseDate").value("2024-05-01"))
            .andExpect(jsonPath("$.items[0].externalRating").value(8.1))
            .andExpect(jsonPath("$.items[0].externalRatingScale").value(10.0))
            .andExpect(jsonPath("$.page").value(11))
            .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test
    void aSecondRequestForTheSamePageIsServedFromCacheWithoutCallingTmdbAgain() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("page", "12").with(loggedIn()))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/movies").param("page", "12").with(loggedIn()))
            .andExpect(status().isOk());

        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void aDifferentPageIsACacheMissAndHitsTmdbAgain() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("page", "13").with(loggedIn()))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/movies").param("page", "14").with(loggedIn()))
            .andExpect(status().isOk());

        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    void defaultsToPageOneWhenNoPageParamIsGiven() throws Exception {
        // The one test in this class that omits ?page entirely — every other
        // test passes an explicit, otherwise-unused page number to avoid
        // colliding with whichever test happens to touch the default (1)
        // first.
        mockMvc.perform(get("/api/catalog/movies").with(loggedIn())).andExpect(status().isOk());

        assertThat(lastQuery.get()).contains("page=1");
    }

    @Test
    void anUnsupportedMediaTypeIs404WithoutCallingTmdb() throws Exception {
        mockMvc.perform(get("/api/catalog/games").with(loggedIn()))
            .andExpect(status().isNotFound());

        assertThat(requestCount.get()).isZero();
    }

    @Test
    void aZeroPageIsRejectedAsBadRequest() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("page", "0").with(loggedIn()))
            .andExpect(status().isBadRequest());

        assertThat(requestCount.get()).isZero();
    }

    @Test
    void aNegativePageIsRejectedAsBadRequest() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("page", "-1").with(loggedIn()))
            .andExpect(status().isBadRequest());

        assertThat(requestCount.get()).isZero();
    }

    @Test
    void anAnonymousRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/catalog/movies")).andExpect(status().isUnauthorized());

        assertThat(requestCount.get()).isZero();
    }

    @Test
    void anEmptyUpstreamResponseReturnsAnEmptyPageRatherThanAnError() throws Exception {
        // resetState() already stubs an empty results array by default.
        mockMvc.perform(get("/api/catalog/movies").param("page", "15").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty())
            .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void anUpstreamFailureSurfacesAsServiceUnavailableRatherThanA500() throws Exception {
        nextResponseStatus.set(500);
        nextResponseBody.set("Internal Server Error");

        mockMvc.perform(get("/api/catalog/movies").param("page", "16").with(loggedIn()))
            .andExpect(status().isServiceUnavailable());

        // ProviderCircuitBreaker is a stateful singleton shared by every test
        // in this class's cached Spring context. A breaker already open from
        // unrelated failures would also surface as 503 without ever calling
        // TMDB — this pins the assertion above to a genuine upstream failure.
        assertThat(requestCount.get()).isEqualTo(1);
    }

    private static RequestPostProcessor loggedIn() {
        return authentication(new UsernamePasswordAuthenticationToken(
            new AuthenticatedUser(1L, "reader@example.com"), null, List.of()));
    }
}
