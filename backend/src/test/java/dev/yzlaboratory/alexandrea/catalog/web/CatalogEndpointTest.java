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
 * end-to-end through the real wiring (TmdbClient, OpenLibraryClient,
 * IgdbClient, CatalogCache, CatalogService) — mirrors {@code
 * AuthEndpointTest}'s shape. Every provider is stood in by a throwaway JDK
 * {@link HttpServer} on a random local port, wired in via the matching
 * {@code alexandrea.catalog.*.base-url} property: the same "swap the outer
 * boundary via a dynamic property, keep everything else real" approach that
 * test already uses for its SQLite datasource.
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

    private static final AtomicReference<String> nextTvResponseBody = new AtomicReference<>();
    private static final AtomicReference<Integer> nextTvResponseStatus = new AtomicReference<>(200);
    private static final AtomicInteger tvRequestCount = new AtomicInteger();

    private static HttpServer openLibraryServer;
    private static final AtomicReference<String> nextOpenLibraryResponseBody = new AtomicReference<>();
    private static final AtomicReference<Integer> nextOpenLibraryResponseStatus = new AtomicReference<>(200);
    private static final AtomicInteger openLibraryRequestCount = new AtomicInteger();

    private static HttpServer igdbGamesServer;
    private static final AtomicReference<String> nextIgdbGamesResponseBody = new AtomicReference<>();
    private static final AtomicReference<Integer> nextIgdbGamesResponseStatus = new AtomicReference<>(200);
    private static final AtomicInteger igdbGamesRequestCount = new AtomicInteger();

    private static HttpServer igdbTwitchServer;
    private static final AtomicInteger igdbTokenRequestCount = new AtomicInteger();

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
            respond(exchange, nextResponseStatus.get(), nextResponseBody.get());
        });
        tmdbServer.createContext("/tv/popular", exchange -> {
            tvRequestCount.incrementAndGet();
            respond(exchange, nextTvResponseStatus.get(), nextTvResponseBody.get());
        });
        tmdbServer.start();
        var tmdbPort = tmdbServer.getAddress().getPort();
        registry.add("alexandrea.catalog.tmdb.base-url", () -> "http://localhost:" + tmdbPort);
        registry.add("alexandrea.catalog.tmdb.api-key", () -> "test-key");

        openLibraryServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        openLibraryServer.createContext("/trending/daily.json", exchange -> {
            openLibraryRequestCount.incrementAndGet();
            respond(exchange, nextOpenLibraryResponseStatus.get(), nextOpenLibraryResponseBody.get());
        });
        openLibraryServer.start();
        var openLibraryPort = openLibraryServer.getAddress().getPort();
        registry.add("alexandrea.catalog.open-library.base-url", () -> "http://localhost:" + openLibraryPort);

        igdbGamesServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        igdbGamesServer.createContext("/games", exchange -> {
            igdbGamesRequestCount.incrementAndGet();
            respond(exchange, nextIgdbGamesResponseStatus.get(), nextIgdbGamesResponseBody.get());
        });
        igdbGamesServer.start();
        var igdbGamesPort = igdbGamesServer.getAddress().getPort();
        registry.add("alexandrea.catalog.igdb.base-url", () -> "http://localhost:" + igdbGamesPort);

        igdbTwitchServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        igdbTwitchServer.createContext("/oauth2/token", exchange -> {
            igdbTokenRequestCount.incrementAndGet();
            respond(exchange, 200, """
                {"access_token": "test-token", "expires_in": 5000000, "token_type": "bearer"}
                """);
        });
        igdbTwitchServer.start();
        var igdbTwitchPort = igdbTwitchServer.getAddress().getPort();
        registry.add("alexandrea.catalog.igdb.twitch-base-url", () -> "http://localhost:" + igdbTwitchPort);
        registry.add("alexandrea.catalog.igdb.client-id", () -> "test-client-id");
        registry.add("alexandrea.catalog.igdb.client-secret", () -> "test-client-secret");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String responseBody) throws IOException {
        var body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(body);
        }
    }

    @AfterAll
    static void stopServers() {
        tmdbServer.stop(0);
        openLibraryServer.stop(0);
        igdbGamesServer.stop(0);
        igdbTwitchServer.stop(0);
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

        tvRequestCount.set(0);
        nextTvResponseStatus.set(200);
        nextTvResponseBody.set("""
            {"page": 1, "results": [], "total_pages": 1}
            """);

        openLibraryRequestCount.set(0);
        nextOpenLibraryResponseStatus.set(200);
        nextOpenLibraryResponseBody.set("""
            {"works": []}
            """);

        igdbGamesRequestCount.set(0);
        nextIgdbGamesResponseStatus.set(200);
        nextIgdbGamesResponseBody.set("[]");
        igdbTokenRequestCount.set(0);
    }

    // CatalogCache and IgdbClient's cached token are singleton beans shared
    // by every test method in this class (one Spring context, cached across
    // the whole class). Each test that reaches the cache uses a page number
    // no other test uses, so a prior test's cached page can never masquerade
    // as this test's response.

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
    void anUnsupportedMediaTypeIs404WithoutCallingAnyProvider() throws Exception {
        mockMvc.perform(get("/api/catalog/podcasts").with(loggedIn()))
            .andExpect(status().isNotFound());

        assertThat(requestCount.get()).isZero();
        assertThat(tvRequestCount.get()).isZero();
        assertThat(openLibraryRequestCount.get()).isZero();
        assertThat(igdbGamesRequestCount.get()).isZero();
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

    @Test
    void returnsAPageOfPopularTvMappedFromTmdbAsSeriesLevelEntries() throws Exception {
        nextTvResponseBody.set("""
            {
              "page": 1,
              "results": [
                {"id": 66732, "name": "A Series", "poster_path": "/s.jpg", "first_air_date": "2016-07-15", "vote_average": 8.6}
              ],
              "total_pages": 5
            }
            """);

        mockMvc.perform(get("/api/catalog/tv").param("page", "1").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].title").value("A Series"))
            .andExpect(jsonPath("$.items[0].mediaType").value("tv"))
            .andExpect(jsonPath("$.items[0].provider").value("TMDB"))
            .andExpect(jsonPath("$.items[0].releaseDate").value("2016-07-15"));
        assertThat(requestCount.get()).isZero();
    }

    @Test
    void aTvUpstreamFailureSurfacesAsServiceUnavailableRatherThanA500() throws Exception {
        nextTvResponseStatus.set(500);
        nextTvResponseBody.set("Internal Server Error");

        mockMvc.perform(get("/api/catalog/tv").param("page", "2").with(loggedIn()))
            .andExpect(status().isServiceUnavailable());

        // TMDB's breaker is shared between movies and tv (same provider);
        // this pins the assertion above to a genuine upstream failure on
        // this request rather than an already-open breaker from unrelated
        // failures elsewhere in this class's cached Spring context.
        assertThat(tvRequestCount.get()).isEqualTo(1);
    }

    @Test
    void returnsAPageOfTrendingBooksWithExternalRatingAlwaysNullForNow() throws Exception {
        // /trending/daily.json carries no rating field at all (see #48) —
        // OpenLibraryClient never populates externalRating for Books yet,
        // regardless of what the upstream response contains.
        nextOpenLibraryResponseBody.set("""
            {
              "works": [
                {"key": "/works/OL1W", "title": "A Book", "cover_i": 123, "first_publish_year": 2011}
              ]
            }
            """);

        mockMvc.perform(get("/api/catalog/books").param("page", "1").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].title").value("A Book"))
            .andExpect(jsonPath("$.items[0].externalId").value("OL1W"))
            .andExpect(jsonPath("$.items[0].mediaType").value("books"))
            .andExpect(jsonPath("$.items[0].provider").value("OpenLibrary"))
            .andExpect(jsonPath("$.items[0].externalRating").doesNotExist())
            .andExpect(jsonPath("$.items[0].externalRatingScale").value(5.0));
    }

    @Test
    void aKeylessWorkIsExcludedFromThePageRatherThanCollidingInTheCache() throws Exception {
        nextOpenLibraryResponseBody.set("""
            {
              "works": [
                {"title": "No Key Book One"},
                {"title": "No Key Book Two"},
                {"key": "/works/OL9W", "title": "A Proper Book"}
              ]
            }
            """);

        mockMvc.perform(get("/api/catalog/books").param("page", "9").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].title").value("A Proper Book"));
    }

    @Test
    void anOpenLibraryUpstreamFailureSurfacesAsServiceUnavailable() throws Exception {
        nextOpenLibraryResponseStatus.set(500);
        nextOpenLibraryResponseBody.set("Internal Server Error");

        mockMvc.perform(get("/api/catalog/books").param("page", "2").with(loggedIn()))
            .andExpect(status().isServiceUnavailable());

        assertThat(openLibraryRequestCount.get()).isEqualTo(1);
    }

    @Test
    void returnsAPageOfPopularGamesMappedFromIgdbViaATwitchToken() throws Exception {
        nextIgdbGamesResponseBody.set("""
            [
              {"id": 1942, "name": "A Game", "cover": {"id": 1, "image_id": "co1wyy"}, "first_release_date": 1431993600, "total_rating": 92.5}
            ]
            """);

        mockMvc.perform(get("/api/catalog/games").param("page", "1").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].title").value("A Game"))
            .andExpect(jsonPath("$.items[0].provider").value("IGDB"))
            .andExpect(jsonPath("$.items[0].mediaType").value("games"))
            .andExpect(jsonPath("$.items[0].coverUrl")
                .value("https://images.igdb.com/igdb/image/upload/t_cover_big/co1wyy.jpg"))
            .andExpect(jsonPath("$.items[0].externalRating").value(92.5))
            .andExpect(jsonPath("$.items[0].externalRatingScale").value(100.0));
    }

    @Test
    void aSecondGamesPageReusesTheCachedTwitchTokenWithoutFetchingItAgain() throws Exception {
        // IgdbClient is a singleton bean shared by every test in this class's
        // cached Spring context, so an earlier games test may already have
        // cached a token — capture the count after this test's own first
        // call rather than assuming it starts at zero, then prove the
        // *second* call doesn't grow it further.
        mockMvc.perform(get("/api/catalog/games").param("page", "3").with(loggedIn())).andExpect(status().isOk());
        var tokenRequestsAfterFirstCall = igdbTokenRequestCount.get();

        mockMvc.perform(get("/api/catalog/games").param("page", "4").with(loggedIn())).andExpect(status().isOk());

        assertThat(igdbGamesRequestCount.get()).isEqualTo(2);
        assertThat(igdbTokenRequestCount.get()).isEqualTo(tokenRequestsAfterFirstCall);
    }

    @Test
    void anIgdbUpstreamFailureSurfacesAsServiceUnavailable() throws Exception {
        nextIgdbGamesResponseStatus.set(500);
        nextIgdbGamesResponseBody.set("Internal Server Error");

        mockMvc.perform(get("/api/catalog/games").param("page", "5").with(loggedIn()))
            .andExpect(status().isServiceUnavailable());

        assertThat(igdbGamesRequestCount.get()).isEqualTo(1);
    }

    private static RequestPostProcessor loggedIn() {
        return authentication(new UsernamePasswordAuthenticationToken(
            new AuthenticatedUser(1L, "reader@example.com"), null, List.of()));
    }
}
