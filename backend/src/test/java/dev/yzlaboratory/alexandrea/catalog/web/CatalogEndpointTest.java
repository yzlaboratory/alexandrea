package dev.yzlaboratory.alexandrea.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import dev.yzlaboratory.alexandrea.auth.AuthenticatedUser;
import dev.yzlaboratory.alexandrea.surface.SurfacePreferenceStore;
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
import org.springframework.jdbc.core.simple.JdbcClient;
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

    private static final AtomicReference<String> nextSearchResponseBody = new AtomicReference<>();
    private static final AtomicReference<Integer> nextSearchResponseStatus = new AtomicReference<>(200);
    private static final AtomicInteger searchRequestCount = new AtomicInteger();
    private static final AtomicReference<String> lastSearchQuery = new AtomicReference<>();

    private static final AtomicReference<String> nextTvSearchResponseBody = new AtomicReference<>();
    private static final AtomicReference<Integer> nextTvSearchResponseStatus = new AtomicReference<>(200);
    private static final AtomicInteger tvSearchRequestCount = new AtomicInteger();

    private static final AtomicReference<String> nextDiscoverResponseBody = new AtomicReference<>();
    private static final AtomicReference<Integer> nextDiscoverResponseStatus = new AtomicReference<>(200);
    private static final AtomicInteger discoverRequestCount = new AtomicInteger();
    private static final AtomicReference<String> lastDiscoverQuery = new AtomicReference<>();

    private static final AtomicReference<String> nextTvDiscoverResponseBody = new AtomicReference<>();
    private static final AtomicInteger tvDiscoverRequestCount = new AtomicInteger();
    private static final AtomicReference<String> lastTvDiscoverQuery = new AtomicReference<>();

    // GenreVocabulary caches its result for the process lifetime (by
    // design — TMDB/IGDB genre enums are near-static), so these fixture
    // bodies are set once and never change across this class's tests,
    // unlike every other response body above: whichever test happens to
    // run first and warm the cache must see the same vocabulary every
    // other test also expects.
    private static final AtomicReference<String> movieGenreListResponseBody = new AtomicReference<>("""
        {"genres": [{"id": 28, "name": "Action"}, {"id": 35, "name": "Comedy"}]}
        """);
    private static final AtomicReference<String> tvGenreListResponseBody = new AtomicReference<>("""
        {"genres": [{"id": 10759, "name": "Action & Adventure"}]}
        """);
    private static final AtomicReference<String> igdbGenreListResponseBody = new AtomicReference<>("""
        [{"id": 5, "name": "Shooter"}, {"id": 12, "name": "Role-playing (RPG)"}]
        """);
    private static final AtomicReference<String> igdbLanguageListResponseBody = new AtomicReference<>("""
        [{"id": 1, "name": "English"}, {"id": 2, "name": "German"}]
        """);

    private static HttpServer openLibraryServer;
    private static final AtomicReference<String> nextOpenLibraryResponseBody = new AtomicReference<>();
    private static final AtomicReference<Integer> nextOpenLibraryResponseStatus = new AtomicReference<>(200);
    private static final AtomicInteger openLibraryRequestCount = new AtomicInteger();

    private static final AtomicReference<String> nextOpenLibrarySearchResponseBody = new AtomicReference<>();
    private static final AtomicReference<Integer> nextOpenLibrarySearchResponseStatus = new AtomicReference<>(200);
    private static final AtomicInteger openLibrarySearchRequestCount = new AtomicInteger();
    private static final AtomicReference<String> lastOpenLibrarySearchQuery = new AtomicReference<>();

    private static HttpServer igdbGamesServer;
    private static final AtomicReference<String> nextIgdbGamesResponseBody = new AtomicReference<>();
    private static final AtomicReference<Integer> nextIgdbGamesResponseStatus = new AtomicReference<>(200);
    private static final AtomicInteger igdbGamesRequestCount = new AtomicInteger();
    private static final AtomicReference<String> lastIgdbGamesRequestBody = new AtomicReference<>();

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
        tmdbServer.createContext("/search/movie", exchange -> {
            searchRequestCount.incrementAndGet();
            lastSearchQuery.set(exchange.getRequestURI().getQuery());
            respond(exchange, nextSearchResponseStatus.get(), nextSearchResponseBody.get());
        });
        tmdbServer.createContext("/search/tv", exchange -> {
            tvSearchRequestCount.incrementAndGet();
            respond(exchange, nextTvSearchResponseStatus.get(), nextTvSearchResponseBody.get());
        });
        tmdbServer.createContext("/discover/movie", exchange -> {
            discoverRequestCount.incrementAndGet();
            lastDiscoverQuery.set(exchange.getRequestURI().getQuery());
            respond(exchange, nextDiscoverResponseStatus.get(), nextDiscoverResponseBody.get());
        });
        tmdbServer.createContext("/discover/tv", exchange -> {
            tvDiscoverRequestCount.incrementAndGet();
            lastTvDiscoverQuery.set(exchange.getRequestURI().getQuery());
            respond(exchange, 200, nextTvDiscoverResponseBody.get());
        });
        tmdbServer.createContext("/genre/movie/list", exchange -> respond(exchange, 200, movieGenreListResponseBody.get()));
        tmdbServer.createContext("/genre/tv/list", exchange -> respond(exchange, 200, tvGenreListResponseBody.get()));
        tmdbServer.start();
        var tmdbPort = tmdbServer.getAddress().getPort();
        registry.add("alexandrea.catalog.tmdb.base-url", () -> "http://localhost:" + tmdbPort);
        registry.add("alexandrea.catalog.tmdb.api-key", () -> "test-key");

        openLibraryServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        openLibraryServer.createContext("/trending/daily.json", exchange -> {
            openLibraryRequestCount.incrementAndGet();
            respond(exchange, nextOpenLibraryResponseStatus.get(), nextOpenLibraryResponseBody.get());
        });
        openLibraryServer.createContext("/search.json", exchange -> {
            openLibrarySearchRequestCount.incrementAndGet();
            lastOpenLibrarySearchQuery.set(exchange.getRequestURI().getQuery());
            respond(exchange, nextOpenLibrarySearchResponseStatus.get(), nextOpenLibrarySearchResponseBody.get());
        });
        openLibraryServer.start();
        var openLibraryPort = openLibraryServer.getAddress().getPort();
        registry.add("alexandrea.catalog.open-library.base-url", () -> "http://localhost:" + openLibraryPort);

        igdbGamesServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        igdbGamesServer.createContext("/games", exchange -> {
            igdbGamesRequestCount.incrementAndGet();
            lastIgdbGamesRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, nextIgdbGamesResponseStatus.get(), nextIgdbGamesResponseBody.get());
        });
        igdbGamesServer.createContext("/genres", exchange -> respond(exchange, 200, igdbGenreListResponseBody.get()));
        igdbGamesServer.createContext("/languages", exchange -> respond(exchange, 200, igdbLanguageListResponseBody.get()));
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

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private SurfacePreferenceStore surfacePreferenceStore;

    // loggedIn()/loggedInAs() fabricate a Spring Security principal directly
    // rather than going through real signup — cheap for every other test in
    // this class, but surface_preferences.user_id carries a real FK to
    // users(id) (ADR 0025's store cascades on account deletion like every
    // other per-user table), so a sort-persisting request 500s with a
    // foreign-key violation unless a matching row actually exists. INSERT OR
    // IGNORE keeps this idempotent across every test method sharing the
    // one class-level database.
    private static final long DEFAULT_TEST_USER_ID = 1L;
    private static final List<Long> TEST_USER_IDS = List.of(
        DEFAULT_TEST_USER_ID, 9001L, 9002L, 9003L, 9004L, 9005L, 9006L, 9007L, 9008L, 9009L, 9010L, 9011L, 9012L,
        9013L, 9014L, 9015L, 9016L, 9017L, 9018L, 9019L, 9020L, 9021L, 9022L, 9023L, 9024L,
        9101L, 9102L, 9103L, 9104L, 9105L, 9106L, 9107L, 9108L, 9109L, 9110L, 9111L, 9112L,
        9201L, 9202L, 9203L, 9204L, 9205L, 9206L, 9207L
    );

    @BeforeEach
    void seedTestUsers() {
        for (var userId : TEST_USER_IDS) {
            jdbcClient
                .sql("""
                    INSERT OR IGNORE INTO users (id, email, password_hash, verified, created_at, updated_at)
                    VALUES (:id, :email, 'hash', 1, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')
                    """)
                .param("id", userId)
                .param("email", "reader" + userId + "@example.com")
                .update();
        }
    }

    // browse() now consults surfacePreferenceStore on every call, including
    // a plain/invalid-value request that resolves to nothing (see
    // CatalogService#browse's "nothing meaningful requested" fast path) —
    // so a loggedIn() test that expects the plain popular/trending feed
    // must start from an actually-clean slate for every media type, not
    // just whatever the previous loggedIn() test happened to leave behind.
    // Dedicated ids (loggedInAs(9xxx)) don't need this: each is used by
    // exactly one test.
    @BeforeEach
    void resetSharedUserPreferences() {
        jdbcClient.sql("DELETE FROM surface_preferences WHERE user_id = :userId").param("userId", DEFAULT_TEST_USER_ID).update();
    }

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

        searchRequestCount.set(0);
        lastSearchQuery.set(null);
        nextSearchResponseStatus.set(200);
        nextSearchResponseBody.set("""
            {"page": 1, "results": [], "total_pages": 1}
            """);

        tvSearchRequestCount.set(0);
        nextTvSearchResponseStatus.set(200);
        nextTvSearchResponseBody.set("""
            {"page": 1, "results": [], "total_pages": 1}
            """);

        discoverRequestCount.set(0);
        lastDiscoverQuery.set(null);
        nextDiscoverResponseStatus.set(200);
        nextDiscoverResponseBody.set("""
            {"page": 1, "results": [], "total_pages": 1}
            """);

        tvDiscoverRequestCount.set(0);
        lastTvDiscoverQuery.set(null);
        nextTvDiscoverResponseBody.set("""
            {"page": 1, "results": [], "total_pages": 1}
            """);

        openLibraryRequestCount.set(0);
        nextOpenLibraryResponseStatus.set(200);
        nextOpenLibraryResponseBody.set("""
            {"works": []}
            """);

        openLibrarySearchRequestCount.set(0);
        lastOpenLibrarySearchQuery.set(null);
        nextOpenLibrarySearchResponseStatus.set(200);
        nextOpenLibrarySearchResponseBody.set("""
            {"docs": []}
            """);

        igdbGamesRequestCount.set(0);
        nextIgdbGamesResponseStatus.set(200);
        nextIgdbGamesResponseBody.set("[]");
        lastIgdbGamesRequestBody.set(null);
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
        // /trending/daily.json carries no rating field at all —
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

    @Test
    void aSearchParamRoutesToTmdbSearchMovieRatherThanThePopularFeed() throws Exception {
        nextSearchResponseBody.set("""
            {
              "page": 1,
              "results": [
                {"id": 78, "title": "Blade Runner", "poster_path": "/blade.jpg", "release_date": "1982-06-25", "vote_average": 7.9}
              ],
              "total_pages": 1
            }
            """);

        mockMvc.perform(get("/api/catalog/movies").param("search", "blade runner").param("page", "30").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].title").value("Blade Runner"));

        assertThat(searchRequestCount.get()).isEqualTo(1);
        assertThat(lastSearchQuery.get()).contains("query=blade");
        assertThat(requestCount.get()).isZero();
    }

    @Test
    void anEmptySearchParamBehavesLikeThePopularFeed() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("search", "").param("page", "31").with(loggedIn()))
            .andExpect(status().isOk());

        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(searchRequestCount.get()).isZero();
    }

    @Test
    void aSearchWithNoMatchesReturnsAnEmptyPageRatherThanAnError() throws Exception {
        // resetState() already stubs an empty results array for search.
        mockMvc.perform(get("/api/catalog/movies").param("search", "zzzznomatch").param("page", "32").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty())
            .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void aSearchUpstreamFailureSurfacesAsServiceUnavailableJustLikePopular() throws Exception {
        nextSearchResponseStatus.set(500);
        nextSearchResponseBody.set("Internal Server Error");

        mockMvc.perform(get("/api/catalog/movies").param("search", "blade runner").param("page", "33").with(loggedIn()))
            .andExpect(status().isServiceUnavailable());

        assertThat(searchRequestCount.get()).isEqualTo(1);
    }

    @Test
    void popularAndSearchForTheSamePageNumberHitTheirOwnEndpointsRatherThanSharingACacheEntry() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("page", "34").with(loggedIn())).andExpect(status().isOk());
        mockMvc.perform(get("/api/catalog/movies").param("search", "blade runner").param("page", "34").with(loggedIn()))
            .andExpect(status().isOk());

        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(searchRequestCount.get()).isEqualTo(1);
    }

    @Test
    void aSearchParamRoutesToTmdbSearchTvRatherThanThePopularFeed() throws Exception {
        nextTvSearchResponseBody.set("""
            {
              "page": 1,
              "results": [
                {"id": 66732, "name": "Stranger Things", "poster_path": "/st.jpg", "first_air_date": "2016-07-15", "vote_average": 8.6}
              ],
              "total_pages": 1
            }
            """);

        mockMvc.perform(get("/api/catalog/tv").param("search", "stranger things").param("page", "1").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].title").value("Stranger Things"));

        assertThat(tvSearchRequestCount.get()).isEqualTo(1);
        assertThat(tvRequestCount.get()).isZero();
    }

    @Test
    void aSearchParamRoutesToOpenLibrarySearchJsonRatherThanTrending() throws Exception {
        nextOpenLibrarySearchResponseBody.set("""
            {
              "docs": [
                {"key": "/works/OL262758W", "title": "Ready Player One", "cover_i": 8235116, "first_publish_year": 2011}
              ]
            }
            """);

        mockMvc.perform(get("/api/catalog/books").param("search", "ready player one").param("page", "1").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].title").value("Ready Player One"));

        assertThat(openLibrarySearchRequestCount.get()).isEqualTo(1);
        assertThat(openLibraryRequestCount.get()).isZero();
    }

    @Test
    void aSearchParamOnGamesReturnsMappedItemsFromIgdb() throws Exception {
        nextIgdbGamesResponseBody.set("""
            [
              {"id": 1942, "name": "The Witcher 3: Wild Hunt", "cover": {"id": 1, "image_id": "co1wyy"}, "first_release_date": 1431993600, "total_rating": 92.5}
            ]
            """);

        mockMvc.perform(get("/api/catalog/games").param("search", "witcher").param("page", "1").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].title").value("The Witcher 3: Wild Hunt"));

        assertThat(igdbGamesRequestCount.get()).isEqualTo(1);
    }

    @Test
    void aSortParamRoutesMoviesToDiscoverRatherThanThePopularFeed() throws Exception {
        nextDiscoverResponseBody.set("""
            {
              "page": 1,
              "results": [
                {"id": 5, "title": "Discovered Movie", "poster_path": "/d.jpg", "release_date": "2022-01-01", "vote_average": 6.5}
              ],
              "total_pages": 1
            }
            """);

        mockMvc.perform(get("/api/catalog/movies").param("sort", "popularity").param("direction", "desc")
                .param("page", "50").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].title").value("Discovered Movie"));

        assertThat(discoverRequestCount.get()).isEqualTo(1);
        assertThat(requestCount.get()).isZero();
        assertThat(lastDiscoverQuery.get()).contains("sort_by=popularity.desc");
    }

    @Test
    void aSortParamRoutesTvToDiscoverTv() throws Exception {
        mockMvc.perform(get("/api/catalog/tv").param("sort", "title").param("direction", "asc")
                .param("page", "51").with(loggedIn()))
            .andExpect(status().isOk());

        assertThat(tvDiscoverRequestCount.get()).isEqualTo(1);
        assertThat(tvRequestCount.get()).isZero();
    }

    @Test
    void aSortParamRoutesGamesToIgdbWithTheChosenSortField() throws Exception {
        mockMvc.perform(get("/api/catalog/games").param("sort", "external_rating").param("direction", "desc")
                .param("page", "1").with(loggedIn()))
            .andExpect(status().isOk());

        assertThat(igdbGamesRequestCount.get()).isEqualTo(1);
    }

    @Test
    void aSortParamRoutesBooksToOpenLibrarySearchRatherThanTrending() throws Exception {
        nextOpenLibrarySearchResponseBody.set("""
            {
              "docs": [
                {"key": "/works/OL1W", "title": "Sorted Book", "ratings_average": 4.5}
              ]
            }
            """);

        mockMvc.perform(get("/api/catalog/books").param("sort", "external_rating").param("direction", "desc")
                .param("page", "1").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].title").value("Sorted Book"))
            .andExpect(jsonPath("$.items[0].externalRating").value(4.5));

        assertThat(openLibrarySearchRequestCount.get()).isEqualTo(1);
        assertThat(openLibraryRequestCount.get()).isZero();
    }

    @Test
    void sortingBooksByExternalRatingExcludesEntriesWithNoOpenLibraryRating() throws Exception {
        nextOpenLibrarySearchResponseBody.set("""
            {
              "docs": [
                {"key": "/works/OL1W", "title": "Rated Book", "ratings_average": 4.5},
                {"key": "/works/OL2W", "title": "Unrated Book"}
              ]
            }
            """);

        mockMvc.perform(get("/api/catalog/books").param("sort", "external_rating").param("direction", "desc")
                .param("page", "2").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].title").value("Rated Book"));
    }

    @Test
    void anUnrecognisedSortKeyFallsBackToThePopularFeedRatherThanErroring() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("sort", "release_year").param("direction", "desc")
                .param("page", "52").with(loggedIn()))
            .andExpect(status().isOk());

        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(discoverRequestCount.get()).isZero();
    }

    @Test
    void aSortAlongsideAnActiveSearchIsIgnoredAndSearchWins() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("search", "blade runner")
                .param("sort", "popularity").param("direction", "desc").param("page", "53").with(loggedIn()))
            .andExpect(status().isOk());

        assertThat(searchRequestCount.get()).isEqualTo(1);
        assertThat(discoverRequestCount.get()).isZero();
    }

    @Test
    void changingSortPersistsImmediatelyAndIsReadBackFromThePreferenceEndpoint() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("sort", "title").param("direction", "asc")
                .param("page", "1").with(loggedIn()))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/movies/preference").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sortKey").value("title"))
            .andExpect(jsonPath("$.sortDirection").value("asc"));
    }

    @Test
    void thePreferenceEndpointReturnsNullFieldsWhenTheUserHasNeverSetAnyForThisMediaType() throws Exception {
        // A userId no other test in this class ever writes a preference
        // for — every test method shares one Spring context and one SQLite
        // database, so asserting "nothing stored" against the common
        // loggedIn() userId would be order-dependent on whichever other
        // preference-writing test happened to run first.
        mockMvc.perform(get("/api/catalog/games/preference").with(loggedInAs(9001L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sortKey").doesNotExist())
            .andExpect(jsonPath("$.sortDirection").doesNotExist())
            .andExpect(jsonPath("$.filters.genre").doesNotExist());
    }

    @Test
    void thePreferenceEndpointIsPerMediaTypeNotSharedAcrossThem() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("sort", "popularity").param("direction", "desc")
                .param("page", "60").with(loggedInAs(9002L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/tv/preference").with(loggedInAs(9002L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sortKey").doesNotExist());
    }

    @Test
    void anAnonymousPreferenceRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/catalog/movies/preference")).andExpect(status().isUnauthorized());
    }

    @Test
    void anUpstreamFailureDuringASortedFetchSurfacesAsServiceUnavailableAndDoesNotPersist() throws Exception {
        nextDiscoverResponseStatus.set(500);
        nextDiscoverResponseBody.set("Internal Server Error");

        mockMvc.perform(get("/api/catalog/movies").param("sort", "popularity").param("direction", "desc")
                .param("page", "61").with(loggedInAs(9003L)))
            .andExpect(status().isServiceUnavailable());

        mockMvc.perform(get("/api/catalog/movies/preference").with(loggedInAs(9003L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sortKey").doesNotExist());
    }

    @Test
    void theBrowseResponseListsTheMovieGenreVocabularyAsAnAvailableFilter() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("page", "80").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableFilters.genre[0].value").value("28"))
            .andExpect(jsonPath("$.availableFilters.genre[0].label").value("Action"))
            .andExpect(jsonPath("$.availableFilters.genre[1].value").value("35"))
            .andExpect(jsonPath("$.availableFilters.genre[1].label").value("Comedy"));
    }

    @Test
    void theBrowseResponseListsTheTvGenreVocabularyAsAnAvailableFilter() throws Exception {
        mockMvc.perform(get("/api/catalog/tv").param("page", "81").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableFilters.genre[0].value").value("10759"))
            .andExpect(jsonPath("$.availableFilters.genre[0].label").value("Action & Adventure"));
    }

    @Test
    void theBrowseResponseListsTheGamesGenreVocabularyAsAnAvailableFilter() throws Exception {
        mockMvc.perform(get("/api/catalog/games").param("page", "82").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableFilters.genre[0].value").value("5"))
            .andExpect(jsonPath("$.availableFilters.genre[0].label").value("Shooter"));
    }

    @Test
    void theBrowseResponseListsTheBooksCuratedGenreVocabularyAsAnAvailableFilter() throws Exception {
        mockMvc.perform(get("/api/catalog/books").param("page", "83").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableFilters.genre[0].value").value("fiction_general"))
            .andExpect(jsonPath("$.availableFilters.genre[0].label").value("Fiction (general)"))
            .andExpect(jsonPath("$.availableFilters.genre[1].value").value("science_fiction"))
            .andExpect(jsonPath("$.availableFilters.genre[1].label").value("Science Fiction"));
    }

    @Test
    void aGenreParamRoutesMoviesToDiscoverWithTheGivenTmdbGenreId() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("genre", "28").param("page", "84").with(loggedIn()))
            .andExpect(status().isOk());

        assertThat(discoverRequestCount.get()).isEqualTo(1);
        assertThat(requestCount.get()).isZero();
        assertThat(lastDiscoverQuery.get()).contains("with_genres=28");
    }

    @Test
    void aGenreParamRoutesTvToDiscoverTvWithTheGivenTmdbGenreId() throws Exception {
        mockMvc.perform(get("/api/catalog/tv").param("genre", "10759").param("page", "85").with(loggedIn()))
            .andExpect(status().isOk());

        assertThat(tvDiscoverRequestCount.get()).isEqualTo(1);
        assertThat(tvRequestCount.get()).isZero();
        assertThat(lastTvDiscoverQuery.get()).contains("with_genres=10759");
    }

    @Test
    void aGenreParamRoutesGamesToIgdbWithAWhereGenresClause() throws Exception {
        mockMvc.perform(get("/api/catalog/games").param("genre", "5").param("page", "86").with(loggedIn()))
            .andExpect(status().isOk());

        assertThat(igdbGamesRequestCount.get()).isEqualTo(1);
        assertThat(lastIgdbGamesRequestBody.get()).contains("where genres = (5);");
    }

    @Test
    void theBrowseResponseListsTheIgdbLanguageEnumAsAnAvailableInLanguageFilterForGames() throws Exception {
        mockMvc.perform(get("/api/catalog/games").param("page", "109").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableFilters.availableInLanguage[0].value").value("1"))
            .andExpect(jsonPath("$.availableFilters.availableInLanguage[0].label").value("English"))
            .andExpect(jsonPath("$.availableFilters.availableInLanguage[1].value").value("2"))
            .andExpect(jsonPath("$.availableFilters.availableInLanguage[1].label").value("German"));
    }

    @Test
    void theBrowseResponseDoesNotListAvailableInLanguageForMoviesOrTv() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("page", "110").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableFilters.availableInLanguage").doesNotExist());

        mockMvc.perform(get("/api/catalog/tv").param("page", "111").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableFilters.availableInLanguage").doesNotExist());
    }

    @Test
    void anAvailableInLanguageParamRoutesGamesToIgdbWithAWhereLanguageSupportsClause() throws Exception {
        // A dedicated user id, not the shared loggedIn() — this persists
        // availableInLanguage for (userId, games), and a later test using
        // the shared loggedIn() id for games (e.g. the plain genre-only
        // test above) must not inherit it as an unexpected extra where
        // clause.
        mockMvc.perform(get("/api/catalog/games").param("availableInLanguage", "2").param("page", "112").with(loggedInAs(9019L)))
            .andExpect(status().isOk());

        assertThat(igdbGamesRequestCount.get()).isEqualTo(1);
        assertThat(lastIgdbGamesRequestBody.get()).contains("where language_supports.language = (2);");
    }

    @Test
    void anInvalidAvailableInLanguageValueIsDroppedAndFallsBackToThePopularFeed() throws Exception {
        mockMvc.perform(get("/api/catalog/games").param("availableInLanguage", "not-a-real-language-id").param("page", "113").with(loggedIn()))
            .andExpect(status().isOk());

        assertThat(lastIgdbGamesRequestBody.get()).doesNotContain("where");
    }

    @Test
    void combiningGenreAndAvailableInLanguageNarrowsGamesToTheIntersection() throws Exception {
        mockMvc.perform(get("/api/catalog/games").param("genre", "5").param("availableInLanguage", "2")
                .param("page", "114").with(loggedInAs(9016L)))
            .andExpect(status().isOk());

        assertThat(lastIgdbGamesRequestBody.get()).contains("where genres = (5) & language_supports.language = (2);");
    }

    @Test
    void anAvailableInLanguageSelectionForGamesPersistsAndIsReadBackFromThePreferenceEndpoint() throws Exception {
        mockMvc.perform(get("/api/catalog/games").param("availableInLanguage", "2").param("page", "115").with(loggedInAs(9017L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/games/preference").with(loggedInAs(9017L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filters.availableInLanguage").value("2"));
    }

    @Test
    void settingAvailableInLanguageDoesNotClobberAPreviouslyPersistedGenreForGames() throws Exception {
        mockMvc.perform(get("/api/catalog/games").param("genre", "5").param("page", "116").with(loggedInAs(9018L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/games").param("availableInLanguage", "2").param("page", "117").with(loggedInAs(9018L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/games/preference").with(loggedInAs(9018L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filters.genre").value("5"))
            .andExpect(jsonPath("$.filters.availableInLanguage").value("2"));
    }

    @Test
    void anInvalidGenreValueIsDroppedAndFallsBackToThePopularFeed() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("genre", "not-a-real-tmdb-genre-id").param("page", "87").with(loggedIn()))
            .andExpect(status().isOk());

        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(discoverRequestCount.get()).isZero();
    }

    @Test
    void theBrowseResponseListsOriginalLanguageAsAnAvailableFilterForMovies() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("page", "98").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableFilters.originalLanguage[0].value").value("en"))
            .andExpect(jsonPath("$.availableFilters.originalLanguage[0].label").value("English"));
    }

    @Test
    void theBrowseResponseListsOriginalLanguageAsAnAvailableFilterForTv() throws Exception {
        mockMvc.perform(get("/api/catalog/tv").param("page", "99").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableFilters.originalLanguage[0].value").value("en"));
    }

    @Test
    void theBrowseResponseDoesNotListOriginalLanguageAsAnAvailableFilterForBooksOrGames() throws Exception {
        mockMvc.perform(get("/api/catalog/books").param("page", "100").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableFilters.originalLanguage").doesNotExist());

        mockMvc.perform(get("/api/catalog/games").param("page", "101").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableFilters.originalLanguage").doesNotExist());
    }

    @Test
    void anOriginalLanguageParamRoutesMoviesToDiscoverWithTheGivenIsoCode() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("originalLanguage", "ja").param("page", "102").with(loggedIn()))
            .andExpect(status().isOk());

        assertThat(discoverRequestCount.get()).isEqualTo(1);
        assertThat(requestCount.get()).isZero();
        assertThat(lastDiscoverQuery.get()).contains("with_original_language=ja");
    }

    @Test
    void anOriginalLanguageParamRoutesTvToDiscoverTvWithTheGivenIsoCode() throws Exception {
        mockMvc.perform(get("/api/catalog/tv").param("originalLanguage", "ko").param("page", "103").with(loggedIn()))
            .andExpect(status().isOk());

        assertThat(tvDiscoverRequestCount.get()).isEqualTo(1);
        assertThat(tvRequestCount.get()).isZero();
        assertThat(lastTvDiscoverQuery.get()).contains("with_original_language=ko");
    }

    @Test
    void anInvalidOriginalLanguageValueIsDroppedAndFallsBackToThePopularFeed() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("originalLanguage", "not-a-real-code").param("page", "104").with(loggedIn()))
            .andExpect(status().isOk());

        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(discoverRequestCount.get()).isZero();
    }

    // Two DIFFERENT filter kinds (genre and original language) combine to
    // the intersection in one request — distinct from, and not in tension
    // with, single-select-per-kind (selectingADifferentGenreReplaces...
    // below), which is about two values of the SAME kind.
    @Test
    void combiningGenreAndOriginalLanguageNarrowsMoviesToTheIntersection() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("genre", "28").param("originalLanguage", "ja")
                .param("page", "105").with(loggedInAs(9013L)))
            .andExpect(status().isOk());

        assertThat(lastDiscoverQuery.get()).contains("with_genres=28").contains("with_original_language=ja");
    }

    @Test
    void anOriginalLanguageSelectionPersistsAndIsReadBackFromThePreferenceEndpoint() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("originalLanguage", "ja").param("page", "106").with(loggedInAs(9014L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/movies/preference").with(loggedInAs(9014L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filters.originalLanguage").value("ja"));
    }

    @Test
    void settingOriginalLanguageDoesNotClobberAPreviouslyPersistedGenre() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("genre", "28").param("page", "107").with(loggedInAs(9015L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/movies").param("originalLanguage", "ja").param("page", "108").with(loggedInAs(9015L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/movies/preference").with(loggedInAs(9015L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filters.genre").value("28"))
            .andExpect(jsonPath("$.filters.originalLanguage").value("ja"));
    }

    @Test
    void aGenreParamRoutesBooksToOpenLibrarySearchWithASubjectQueryBuiltFromTheCuratedAliases() throws Exception {
        nextOpenLibrarySearchResponseBody.set("""
            {"docs": [{"key": "/works/OL1W", "title": "A Sci-Fi Book"}]}
            """);

        // A dedicated user id, not the shared loggedIn() — an earlier test in
        // this class's shared Spring context persists an external_rating
        // sort for (loggedIn(), books), and sortedBooks excludes unrated
        // entries under that sort (ADR 0006); this test's unrated stub book
        // would then vanish for a reason unrelated to what it's testing.
        mockMvc.perform(get("/api/catalog/books").param("genre", "science_fiction").param("page", "88").with(loggedInAs(9009L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].title").value("A Sci-Fi Book"));

        assertThat(openLibrarySearchRequestCount.get()).isEqualTo(1);
        assertThat(openLibraryRequestCount.get()).isZero();
        assertThat(lastOpenLibrarySearchQuery.get())
            .contains("q=subject:(\"Science fiction\" OR \"Sci-Fi\" OR \"Science-fiction\" OR \"Speculative fiction\")");
    }

    @Test
    void anInvalidBooksGenreValueIsDroppedAndFallsBackToTheTrendingFeed() throws Exception {
        mockMvc.perform(get("/api/catalog/books").param("genre", "not-a-curated-genre").param("page", "89").with(loggedInAs(9010L)))
            .andExpect(status().isOk());

        assertThat(openLibraryRequestCount.get()).isEqualTo(1);
        assertThat(openLibrarySearchRequestCount.get()).isZero();
    }

    @Test
    void theBrowseResponseListsTheCuratedMarc3VocabularyAsAnAvailableInLanguageFilterForBooks() throws Exception {
        mockMvc.perform(get("/api/catalog/books").param("page", "118").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableFilters.availableInLanguage[0].value").value("eng"))
            .andExpect(jsonPath("$.availableFilters.availableInLanguage[0].label").value("English"));
    }

    @Test
    void anAvailableInLanguageParamRoutesBooksToOpenLibrarySearchWithALanguageQuery() throws Exception {
        // A dedicated user id, not the shared loggedIn() — this persists
        // availableInLanguage for (userId, books), which a later test using
        // the shared loggedIn() id for books must not inherit.
        mockMvc.perform(get("/api/catalog/books").param("availableInLanguage", "ger").param("page", "119").with(loggedInAs(9020L)))
            .andExpect(status().isOk());

        assertThat(openLibrarySearchRequestCount.get()).isEqualTo(1);
        assertThat(openLibraryRequestCount.get()).isZero();
        assertThat(lastOpenLibrarySearchQuery.get()).contains("q=language:ger");
    }

    @Test
    void anInvalidBooksAvailableInLanguageValueIsDroppedAndFallsBackToTheTrendingFeed() throws Exception {
        mockMvc.perform(get("/api/catalog/books").param("availableInLanguage", "not-a-real-marc3-code")
                .param("page", "120").with(loggedInAs(9021L)))
            .andExpect(status().isOk());

        assertThat(openLibraryRequestCount.get()).isEqualTo(1);
        assertThat(openLibrarySearchRequestCount.get()).isZero();
    }

    @Test
    void combiningGenreAndAvailableInLanguageNarrowsBooksToTheIntersection() throws Exception {
        mockMvc.perform(get("/api/catalog/books").param("genre", "science_fiction").param("availableInLanguage", "ger")
                .param("page", "121").with(loggedInAs(9022L)))
            .andExpect(status().isOk());

        assertThat(lastOpenLibrarySearchQuery.get())
            .contains("q=subject:(\"Science fiction\" OR \"Sci-Fi\" OR \"Science-fiction\" OR \"Speculative fiction\") AND language:ger");
    }

    @Test
    void anAvailableInLanguageSelectionForBooksPersistsAndIsReadBackFromThePreferenceEndpoint() throws Exception {
        mockMvc.perform(get("/api/catalog/books").param("availableInLanguage", "ger").param("page", "122").with(loggedInAs(9023L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/books/preference").with(loggedInAs(9023L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filters.availableInLanguage").value("ger"));
    }

    @Test
    void settingAvailableInLanguageDoesNotClobberAPreviouslyPersistedGenreForBooks() throws Exception {
        mockMvc.perform(get("/api/catalog/books").param("genre", "science_fiction").param("page", "123").with(loggedInAs(9024L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/books").param("availableInLanguage", "ger").param("page", "124").with(loggedInAs(9024L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/books/preference").with(loggedInAs(9024L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filters.genre").value("science_fiction"))
            .andExpect(jsonPath("$.filters.availableInLanguage").value("ger"));
    }

    // OpenLibrary's own subject tagging (not something this app enforces)
    // is what actually makes a book match more than one curated genre — this
    // proves our own routing doesn't artificially withhold a result from a
    // filter view just because it also matched a different genre filter
    // applied in a separate request.
    @Test
    void aBookMatchingMultipleCuratedGenresAppearsUnderEachWhenFilteredSeparately() throws Exception {
        nextOpenLibrarySearchResponseBody.set("""
            {"docs": [{"key": "/works/OL1W", "title": "A Cross-Genre Book"}]}
            """);

        mockMvc.perform(get("/api/catalog/books").param("genre", "fantasy").param("page", "150").with(loggedInAs(9011L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].title").value("A Cross-Genre Book"));
        var fantasyQuery = lastOpenLibrarySearchQuery.get();

        mockMvc.perform(get("/api/catalog/books").param("genre", "young_adult").param("page", "151").with(loggedInAs(9011L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].title").value("A Cross-Genre Book"));
        var youngAdultQuery = lastOpenLibrarySearchQuery.get();

        assertThat(fantasyQuery).isNotEqualTo(youngAdultQuery);
        assertThat(openLibrarySearchRequestCount.get()).isEqualTo(2);
    }

    @Test
    void aBooksGenreSelectionPersistsAndIsReadBackFromThePreferenceEndpointJustLikeMovies() throws Exception {
        mockMvc.perform(get("/api/catalog/books").param("genre", "fantasy").param("page", "152").with(loggedInAs(9012L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/books/preference").with(loggedInAs(9012L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filters.genre").value("fantasy"));
    }

    @Test
    void aGenreSelectionPersistsAndIsReadBackFromThePreferenceEndpointAlongsideSort() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("sort", "title").param("direction", "asc")
                .param("genre", "28").param("page", "89").with(loggedInAs(9004L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/movies/preference").with(loggedInAs(9004L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sortKey").value("title"))
            .andExpect(jsonPath("$.sortDirection").value("asc"))
            .andExpect(jsonPath("$.filters.genre").value("28"));
    }

    @Test
    void applyingOnlyAGenreAfterASortWasAlreadyPersistedDoesNotClobberTheSort() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("sort", "title").param("direction", "asc")
                .param("page", "90").with(loggedInAs(9005L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/movies").param("genre", "28").param("page", "91").with(loggedInAs(9005L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/movies/preference").with(loggedInAs(9005L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sortKey").value("title"))
            .andExpect(jsonPath("$.sortDirection").value("asc"))
            .andExpect(jsonPath("$.filters.genre").value("28"));
    }

    @Test
    void changingOnlyTheSortAfterAGenreWasAlreadyPersistedDoesNotClobberTheGenre() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("genre", "28").param("page", "92").with(loggedInAs(9006L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/movies").param("sort", "release_date").param("direction", "desc")
                .param("page", "93").with(loggedInAs(9006L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/movies/preference").with(loggedInAs(9006L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sortKey").value("release_date"))
            .andExpect(jsonPath("$.sortDirection").value("desc"))
            .andExpect(jsonPath("$.filters.genre").value("28"));
    }

    @Test
    void selectingADifferentGenreReplacesRatherThanCombiningWithThePrevious() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("genre", "28").param("page", "94").with(loggedInAs(9007L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/movies").param("genre", "35").param("page", "95").with(loggedInAs(9007L)))
            .andExpect(status().isOk());

        assertThat(lastDiscoverQuery.get()).contains("with_genres=35").doesNotContain("with_genres=28");
        mockMvc.perform(get("/api/catalog/movies/preference").with(loggedInAs(9007L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filters.genre").value("35"));
    }

    // A deselected filter chip sends a present-but-empty genre= — distinct
    // from omitting the param, which instead falls back to whatever's
    // persisted (the read-merge-write tests above). Without this
    // distinction, deselecting a genre could never actually clear it.
    @Test
    void anExplicitlyEmptyGenreParamClearsAPreviouslySelectedGenre() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("sort", "title").param("direction", "asc")
                .param("genre", "28").param("page", "96").with(loggedInAs(9008L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/movies").param("sort", "title").param("direction", "asc")
                .param("genre", "").param("page", "97").with(loggedInAs(9008L)))
            .andExpect(status().isOk());

        assertThat(lastDiscoverQuery.get()).doesNotContain("with_genres");
        mockMvc.perform(get("/api/catalog/movies/preference").with(loggedInAs(9008L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sortKey").value("title"))
            .andExpect(jsonPath("$.filters.genre").doesNotExist());
    }

    // --- Runtime (Movies/TV) and page count (Books) — ADR 0018's two
    // range filters. Both encode as a single opaque "<min>,<max>" string
    // value, the same shape every other filter kind's persisted value
    // already has, so no new persistence mechanism is exercised here
    // beyond the existing read-merge-write path.

    @Test
    void theBrowseResponseListsRuntimeAsAnAvailableFilterForMoviesAndTv() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("page", "200").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableFilters.runtime").isArray())
            .andExpect(jsonPath("$.availableFilters.runtime").isEmpty());

        mockMvc.perform(get("/api/catalog/tv").param("page", "201").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableFilters.runtime").isArray());
    }

    @Test
    void theBrowseResponseDoesNotListRuntimeForBooksOrGames() throws Exception {
        mockMvc.perform(get("/api/catalog/books").param("page", "202").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableFilters.runtime").doesNotExist());

        mockMvc.perform(get("/api/catalog/games").param("page", "203").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableFilters.runtime").doesNotExist());
    }

    @Test
    void aRuntimeParamRoutesMoviesToDiscoverWithTheGivenRange() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("runtime", "90,180").param("page", "204").with(loggedInAs(9101L)))
            .andExpect(status().isOk());

        assertThat(discoverRequestCount.get()).isEqualTo(1);
        assertThat(requestCount.get()).isZero();
        assertThat(lastDiscoverQuery.get()).contains("with_runtime.gte=90").contains("with_runtime.lte=180");
    }

    @Test
    void aRuntimeParamRoutesTvToDiscoverTvWithTheGivenRange() throws Exception {
        mockMvc.perform(get("/api/catalog/tv").param("runtime", "20,60").param("page", "205").with(loggedInAs(9102L)))
            .andExpect(status().isOk());

        assertThat(tvDiscoverRequestCount.get()).isEqualTo(1);
        assertThat(tvRequestCount.get()).isZero();
        assertThat(lastTvDiscoverQuery.get()).contains("with_runtime.gte=20").contains("with_runtime.lte=60");
    }

    @Test
    void aMalformedRuntimeValueIsDroppedAndFallsBackToThePopularFeed() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("runtime", "not-a-range").param("page", "206").with(loggedIn()))
            .andExpect(status().isOk());

        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(discoverRequestCount.get()).isZero();
    }

    @Test
    void combiningGenreAndRuntimeNarrowsMoviesToTheIntersection() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("genre", "28").param("runtime", "90,180")
                .param("page", "207").with(loggedInAs(9103L)))
            .andExpect(status().isOk());

        assertThat(lastDiscoverQuery.get()).contains("with_genres=28").contains("with_runtime.gte=90").contains("with_runtime.lte=180");
    }

    @Test
    void aRuntimeSelectionPersistsAndIsReadBackFromThePreferenceEndpoint() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("runtime", "90,180").param("page", "208").with(loggedInAs(9104L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/movies/preference").with(loggedInAs(9104L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filters.runtime").value("90,180"));
    }

    @Test
    void settingRuntimeDoesNotClobberAPreviouslyPersistedGenre() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("genre", "28").param("page", "209").with(loggedInAs(9105L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/movies").param("runtime", "90,180").param("page", "210").with(loggedInAs(9105L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/movies/preference").with(loggedInAs(9105L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filters.genre").value("28"))
            .andExpect(jsonPath("$.filters.runtime").value("90,180"));
    }

    @Test
    void theBrowseResponseListsPageCountAsAnAvailableFilterForBooks() throws Exception {
        mockMvc.perform(get("/api/catalog/books").param("page", "211").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableFilters.pageCount").isArray())
            .andExpect(jsonPath("$.availableFilters.pageCount").isEmpty());
    }

    @Test
    void theBrowseResponseDoesNotListPageCountForMoviesTvOrGames() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("page", "212").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableFilters.pageCount").doesNotExist());

        mockMvc.perform(get("/api/catalog/tv").param("page", "213").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableFilters.pageCount").doesNotExist());

        mockMvc.perform(get("/api/catalog/games").param("page", "214").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableFilters.pageCount").doesNotExist());
    }

    @Test
    void aPageCountParamRoutesBooksToOpenLibrarySearchWithANumberOfPagesMedianClause() throws Exception {
        mockMvc.perform(get("/api/catalog/books").param("pageCount", "300,400").param("page", "215").with(loggedInAs(9106L)))
            .andExpect(status().isOk());

        assertThat(openLibrarySearchRequestCount.get()).isEqualTo(1);
        assertThat(openLibraryRequestCount.get()).isZero();
        assertThat(lastOpenLibrarySearchQuery.get()).contains("q=number_of_pages_median:[300 TO 400]");
    }

    @Test
    void aMalformedPageCountValueIsDroppedAndFallsBackToTheTrendingFeed() throws Exception {
        mockMvc.perform(get("/api/catalog/books").param("pageCount", "not-a-range").param("page", "216").with(loggedIn()))
            .andExpect(status().isOk());

        assertThat(openLibraryRequestCount.get()).isEqualTo(1);
        assertThat(openLibrarySearchRequestCount.get()).isZero();
    }

    @Test
    void combiningGenreAndPageCountNarrowsBooksToTheIntersection() throws Exception {
        mockMvc.perform(get("/api/catalog/books").param("genre", "science_fiction").param("pageCount", "300,400")
                .param("page", "217").with(loggedInAs(9107L)))
            .andExpect(status().isOk());

        assertThat(lastOpenLibrarySearchQuery.get())
            .contains("q=subject:(\"Science fiction\" OR \"Sci-Fi\" OR \"Science-fiction\" OR \"Speculative fiction\") AND number_of_pages_median:[300 TO 400]");
    }

    @Test
    void aPageCountSelectionPersistsAndIsReadBackFromThePreferenceEndpoint() throws Exception {
        mockMvc.perform(get("/api/catalog/books").param("pageCount", "300,400").param("page", "218").with(loggedInAs(9108L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/books/preference").with(loggedInAs(9108L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filters.pageCount").value("300,400"));
    }

    @Test
    void settingPageCountDoesNotClobberAPreviouslyPersistedGenreForBooks() throws Exception {
        mockMvc.perform(get("/api/catalog/books").param("genre", "science_fiction").param("page", "219").with(loggedInAs(9109L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/books").param("pageCount", "300,400").param("page", "220").with(loggedInAs(9109L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/books/preference").with(loggedInAs(9109L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filters.genre").value("science_fiction"))
            .andExpect(jsonPath("$.filters.pageCount").value("300,400"));
    }

    // Three DIFFERENT filter kinds at once — genre, original language, and
    // runtime — not just the pairs covered above; proves the read-merge-
    // write mechanism keeps resolving each field independently regardless
    // of how many other fields ride along in the same request.
    @Test
    void combiningGenreOriginalLanguageAndRuntimeNarrowsMoviesToTheIntersection() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("genre", "28").param("originalLanguage", "ja").param("runtime", "90,180")
                .param("page", "221").with(loggedInAs(9110L)))
            .andExpect(status().isOk());

        assertThat(lastDiscoverQuery.get())
            .contains("with_genres=28").contains("with_original_language=ja")
            .contains("with_runtime.gte=90").contains("with_runtime.lte=180");
    }

    // "Clear filters" — one request clearing every active filter kind for
    // this (user, surface, media_type) via the same present-but-empty
    // sentinel each field already honors individually (see
    // anExplicitlyEmptyGenreParamClearsAPreviouslySelectedGenre above), while
    // sort is left completely alone.
    @Test
    void clearFiltersResetsAllActiveFiltersInOneRequestButLeavesSortUnchanged() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("sort", "title").param("direction", "asc")
                .param("genre", "28").param("originalLanguage", "ja").param("runtime", "90,180")
                .param("page", "222").with(loggedInAs(9111L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/movies")
                .param("genre", "").param("originalLanguage", "").param("runtime", "")
                .param("page", "223").with(loggedInAs(9111L)))
            .andExpect(status().isOk());

        assertThat(lastDiscoverQuery.get())
            .doesNotContain("with_genres").doesNotContain("with_original_language").doesNotContain("with_runtime");
        mockMvc.perform(get("/api/catalog/movies/preference").with(loggedInAs(9111L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sortKey").value("title"))
            .andExpect(jsonPath("$.sortDirection").value("asc"))
            .andExpect(jsonPath("$.filters.genre").doesNotExist())
            .andExpect(jsonPath("$.filters.originalLanguage").doesNotExist())
            .andExpect(jsonPath("$.filters.runtime").doesNotExist());
        // Verified directly against the store, not just the API response:
        // encodeFilters collapses an empty resolved map to a SQL NULL, so
        // the row is genuinely empty rather than an empty-but-present "{}".
        assertThat(surfacePreferenceStore.get(9111L, "catalog", "movies")).hasValueSatisfying(
            stored -> assertThat(stored.filters()).isNull()
        );
    }

    @Test
    void clearingFiltersThatWereNeverSetIsASafeNoOp() throws Exception {
        mockMvc.perform(get("/api/catalog/movies")
                .param("genre", "").param("originalLanguage", "").param("runtime", "")
                .param("page", "224").with(loggedInAs(9112L)))
            .andExpect(status().isOk());

        assertThat(lastDiscoverQuery.get())
            .doesNotContain("with_genres").doesNotContain("with_original_language").doesNotContain("with_runtime");
        assertThat(surfacePreferenceStore.get(9112L, "catalog", "movies")).hasValueSatisfying(
            stored -> assertThat(stored.filters()).isNull()
        );
    }

    // --- A text search disables the sort/filter fields ADR 0018's
    // "Behavior under active text search" table says the provider's
    // search endpoint can't honor, signals which ones via disabledFilters/
    // sortDisabled on the browse response, and restores the previously
    // applied sort/filters once the search clears.

    @Test
    void theBrowseResponseReportsNothingDisabledWhenNoSearchIsActive() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("page", "300").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.disabledFilters").isEmpty())
            .andExpect(jsonPath("$.sortDisabled").value(false));
    }

    @Test
    void theBrowseResponseDisablesGenreOriginalLanguageRuntimeAndSortForMoviesWhileSearching() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("search", "blade runner").param("page", "301").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.disabledFilters", org.hamcrest.Matchers.containsInAnyOrder("genre", "originalLanguage", "runtime")))
            .andExpect(jsonPath("$.sortDisabled").value(true));
    }

    @Test
    void theBrowseResponseDisablesTheSameFieldsForTvWhileSearching() throws Exception {
        mockMvc.perform(get("/api/catalog/tv").param("search", "stranger things").param("page", "302").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.disabledFilters", org.hamcrest.Matchers.containsInAnyOrder("genre", "originalLanguage", "runtime")))
            .andExpect(jsonPath("$.sortDisabled").value(true));
    }

    @Test
    void theBrowseResponseDisablesOnlySortForGamesWhileSearching() throws Exception {
        mockMvc.perform(get("/api/catalog/games").param("search", "witcher").param("page", "303").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.disabledFilters").isEmpty())
            .andExpect(jsonPath("$.sortDisabled").value(true));
    }

    @Test
    void theBrowseResponseDisablesNothingForBooksWhileSearching() throws Exception {
        mockMvc.perform(get("/api/catalog/books").param("search", "dune").param("page", "304").with(loggedIn()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.disabledFilters").isEmpty())
            .andExpect(jsonPath("$.sortDisabled").value(false));
    }

    @Test
    void aGenreParamDuringAGamesSearchCombinesTheSearchClauseWithAWhereClause() throws Exception {
        mockMvc.perform(get("/api/catalog/games").param("search", "witcher").param("genre", "5")
                .param("page", "305").with(loggedInAs(9201L)))
            .andExpect(status().isOk());

        assertThat(lastIgdbGamesRequestBody.get()).contains("search \"witcher\";").contains("where genres = (5);");
    }

    @Test
    void aSortParamDuringAGamesSearchIsDroppedRatherThanReachingIgdb() throws Exception {
        mockMvc.perform(get("/api/catalog/games").param("search", "witcher").param("sort", "title").param("direction", "asc")
                .param("page", "306").with(loggedInAs(9202L)))
            .andExpect(status().isOk());

        assertThat(lastIgdbGamesRequestBody.get()).contains("search \"witcher\";").doesNotContain("sort");
    }

    @Test
    void aSortAndGenreParamDuringABooksSearchCombineIntoOneOpenLibraryRequest() throws Exception {
        mockMvc.perform(get("/api/catalog/books").param("search", "dune").param("sort", "external_rating").param("direction", "desc")
                .param("genre", "science_fiction").param("page", "307").with(loggedInAs(9203L)))
            .andExpect(status().isOk());

        assertThat(lastOpenLibrarySearchQuery.get())
            .contains("q=dune")
            .contains("subject:(\"Science fiction\"")
            .contains("sort=rating");
    }

    @Test
    void sortingBooksByExternalRatingDuringASearchStillExcludesUnratedEntriesPerAdr0006() throws Exception {
        nextOpenLibrarySearchResponseBody.set("""
            {
              "docs": [
                {"key": "/works/OL1W", "title": "Rated Dune Edition", "ratings_average": 4.5},
                {"key": "/works/OL2W", "title": "Unrated Dune Edition"}
              ]
            }
            """);

        mockMvc.perform(get("/api/catalog/books").param("search", "dune").param("sort", "external_rating").param("direction", "desc")
                .param("page", "308").with(loggedInAs(9204L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].title").value("Rated Dune Edition"));
    }

    @Test
    void aFilterSelectedDuringAGamesSearchPersistsAndIsReadBackFromThePreferenceEndpoint() throws Exception {
        mockMvc.perform(get("/api/catalog/games").param("search", "witcher").param("genre", "5")
                .param("page", "309").with(loggedInAs(9205L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/games/preference").with(loggedInAs(9205L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filters.genre").value("5"));
    }

    @Test
    void aSortAndFilterSelectedDuringABooksSearchPersistAndAreReadBackFromThePreferenceEndpoint() throws Exception {
        mockMvc.perform(get("/api/catalog/books").param("search", "dune").param("sort", "title").param("direction", "asc")
                .param("genre", "science_fiction").param("page", "310").with(loggedInAs(9206L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/books/preference").with(loggedInAs(9206L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sortKey").value("title"))
            .andExpect(jsonPath("$.sortDirection").value("asc"))
            .andExpect(jsonPath("$.filters.genre").value("science_fiction"));
    }

    // A search attempting to change Movies' locked genre/sort must leave the
    // previously persisted values completely untouched (defensive
    // backstop — the real frontend never sends a changed value for a
    // locked control in the first place) — clearing the search and
    // resending the original values then reaches TMDB with them intact.
    @Test
    void aMoviesSearchNeverClobbersThePreviouslyPersistedGenreAndSortWhichReactivateOnceSearchClears() throws Exception {
        mockMvc.perform(get("/api/catalog/movies").param("sort", "title").param("direction", "asc")
                .param("genre", "28").param("page", "311").with(loggedInAs(9207L)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/movies").param("search", "blade runner")
                .param("sort", "popularity").param("direction", "desc").param("genre", "35")
                .param("page", "312").with(loggedInAs(9207L)))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/catalog/movies/preference").with(loggedInAs(9207L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sortKey").value("title"))
            .andExpect(jsonPath("$.filters.genre").value("28"));

        mockMvc.perform(get("/api/catalog/movies").param("sort", "title").param("direction", "asc")
                .param("genre", "28").param("page", "313").with(loggedInAs(9207L)))
            .andExpect(status().isOk());

        assertThat(lastDiscoverQuery.get()).contains("with_genres=28").contains("sort_by=original_title.asc");
    }

    private static RequestPostProcessor loggedIn() {
        return loggedInAs(1L);
    }

    private static RequestPostProcessor loggedInAs(long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(
            new AuthenticatedUser(userId, "reader" + userId + "@example.com"), null, List.of()));
    }
}
