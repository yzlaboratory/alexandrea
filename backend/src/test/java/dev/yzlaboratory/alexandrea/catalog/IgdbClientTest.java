package dev.yzlaboratory.alexandrea.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.yzlaboratory.alexandrea.auth.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Request shape, response mapping, and Twitch client-credentials token
 * lifecycle for IGDB's {@code /games} endpoint, against captured/mocked HTTP
 * responses rather than a live call (per the feature ticket's testing
 * decisions).
 */
class IgdbClientTest {

    private static final String GAMES_BASE_URL = "https://api.igdb.com/v4";
    private static final String TWITCH_BASE_URL = "https://id.twitch.tv";

    private MockRestServiceServer gamesServer;
    private MockRestServiceServer twitchServer;
    private MutableClock clock;
    private IgdbClient client;

    @BeforeEach
    void setUp() {
        var properties = new CatalogProperties(
            null,
            null,
            new CatalogProperties.Igdb(
                GAMES_BASE_URL,
                TWITCH_BASE_URL,
                "https://images.igdb.com/igdb/image/upload/t_cover_big/",
                "test-client-id",
                "test-client-secret"));
        var gamesBuilder = RestClient.builder().baseUrl(GAMES_BASE_URL);
        var twitchBuilder = RestClient.builder().baseUrl(TWITCH_BASE_URL);
        gamesServer = MockRestServiceServer.bindTo(gamesBuilder).build();
        twitchServer = MockRestServiceServer.bindTo(twitchBuilder).build();
        clock = new MutableClock(Instant.parse("2026-06-07T12:00:00Z"));
        client = new IgdbClient(gamesBuilder.build(), twitchBuilder.build(), properties, clock);
    }

    @Test
    void fetchesATwitchTokenLazilyOnFirstUseThenCallsGamesWithIt() {
        expectTokenRequest().andRespond(tokenResponse("token-1", 5_000_000));
        expectGamesRequest()
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-1"))
            .andExpect(header("Client-ID", "test-client-id"))
            .andRespond(withSuccess("""
                [
                  {
                    "id": 1942,
                    "name": "The Witcher 3: Wild Hunt",
                    "cover": {"id": 89386, "image_id": "co1wyy"},
                    "first_release_date": 1431993600,
                    "total_rating": 92.5
                  }
                ]
                """, MediaType.APPLICATION_JSON));

        var result = client.popularGames(1);

        assertThat(result.items()).hasSize(1);
        var item = result.items().getFirst();
        assertThat(item.provider()).isEqualTo("IGDB");
        assertThat(item.externalId()).isEqualTo("1942");
        assertThat(item.mediaType()).isEqualTo("games");
        assertThat(item.title()).isEqualTo("The Witcher 3: Wild Hunt");
        assertThat(item.coverUrl())
            .isEqualTo("https://images.igdb.com/igdb/image/upload/t_cover_big/co1wyy.jpg");
        assertThat(item.releaseDate())
            .isEqualTo(Instant.ofEpochSecond(1431993600L).atZone(ZoneOffset.UTC).toLocalDate());
        assertThat(item.externalRating()).isEqualTo(92.5);
        assertThat(item.externalRatingScale()).isEqualTo(100.0);
        gamesServer.verify();
        twitchServer.verify();
    }

    @Test
    void aSecondRequestReusesTheCachedTokenWithoutFetchingANewOne() {
        expectTokenRequest().andRespond(tokenResponse("token-1", 5_000_000));
        expectGamesRequest().andRespond(emptyGamesResponse());
        expectGamesRequest().andRespond(emptyGamesResponse());

        client.popularGames(1);
        client.popularGames(2);

        // Only one token expectation was ever queued on twitchServer — a
        // second, unexpected call would fail verify() below.
        gamesServer.verify();
        twitchServer.verify();
    }

    @Test
    void aTokenNearingItsExpiryIsRefetchedRatherThanReusedPastItsSafetyMargin() {
        // MockRestServiceServer's default expectation manager rejects new
        // expectations once actual requests start, so every expectation for
        // this test — across both popularGames() calls — is queued upfront.
        expectTokenRequest().andRespond(tokenResponse("token-1", 100));
        expectGamesRequest().andRespond(emptyGamesResponse());
        expectTokenRequest().andRespond(tokenResponse("token-2", 100));
        expectGamesRequest()
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-2"))
            .andRespond(emptyGamesResponse());

        client.popularGames(1);
        // isExpiredAt treats the last 30s before real expiry as already
        // expired, so 71 elapsed seconds against a 100s token crosses that
        // margin and forces a refetch on this next call.
        clock.advance(Duration.ofSeconds(71));
        client.popularGames(2);

        gamesServer.verify();
        twitchServer.verify();
    }

    @Test
    void aFourZeroOneFromGamesForcesOneTokenRefetchAndRetry() {
        expectTokenRequest().andRespond(tokenResponse("stale-token", 5_000_000));
        expectGamesRequest()
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer stale-token"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectTokenRequest().andRespond(tokenResponse("fresh-token", 5_000_000));
        expectGamesRequest()
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fresh-token"))
            .andRespond(emptyGamesResponse());

        var result = client.popularGames(1);

        assertThat(result.items()).isEmpty();
        gamesServer.verify();
        twitchServer.verify();
    }

    @Test
    void aFourZeroOneOnTheRetryItselfGivesUpRatherThanRetryingForever() {
        expectTokenRequest().andRespond(tokenResponse("token-1", 5_000_000));
        expectGamesRequest().andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectTokenRequest().andRespond(tokenResponse("token-2", 5_000_000));
        expectGamesRequest().andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.popularGames(1)).isInstanceOf(CatalogUpstreamException.class);
        // Exactly two token fetches and two games calls — a third round
        // would mean the retry-once rule wasn't honoured.
        gamesServer.verify();
        twitchServer.verify();
    }

    @Test
    void anUpstream5xxOnGamesIsWrappedAsACatalogUpstreamExceptionWithoutRetrying() {
        expectTokenRequest().andRespond(tokenResponse("token-1", 5_000_000));
        expectGamesRequest().andRespond(withServerError());

        assertThatThrownBy(() -> client.popularGames(1)).isInstanceOf(CatalogUpstreamException.class);
        gamesServer.verify();
        twitchServer.verify();
    }

    @Test
    void anUpstreamFailureFetchingTheTokenItselfIsWrappedAsACatalogUpstreamException() {
        expectTokenRequest().andRespond(withServerError());

        assertThatThrownBy(() -> client.popularGames(1)).isInstanceOf(CatalogUpstreamException.class);
    }

    @Test
    void anEmptyTokenResponseBodyCarriesACauseRatherThanUsingTheBreakerNoOpConstructor() {
        // A 200 with no body is a real round trip that produced an anomaly,
        // not the circuit breaker's "no call attempted" no-op — it must use
        // CatalogUpstreamException's cause-carrying constructor so it isn't
        // logged identically to a breaker short-circuit.
        expectTokenRequest().andRespond(withStatus(HttpStatus.OK));

        assertThatThrownBy(() -> client.popularGames(1))
            .isInstanceOf(CatalogUpstreamException.class)
            .satisfies(thrown -> assertThat(thrown.getCause()).isNotNull());
    }

    @Test
    void aGameWithNoRatingMapsToNullRatherThanZero() {
        expectTokenRequest().andRespond(tokenResponse("token-1", 5_000_000));
        expectGamesRequest().andRespond(withSuccess("""
            [{"id": 7, "name": "Unrated Game"}]
            """, MediaType.APPLICATION_JSON));

        var item = client.popularGames(1).items().getFirst();

        assertThat(item.externalRating()).isNull();
    }

    @Test
    void aGameWithNoCoverMapsToNullCoverUrlRatherThanCrashing() {
        expectTokenRequest().andRespond(tokenResponse("token-1", 5_000_000));
        expectGamesRequest().andRespond(withSuccess("""
            [{"id": 8, "name": "No Cover Game", "cover": null}]
            """, MediaType.APPLICATION_JSON));

        var item = client.popularGames(1).items().getFirst();

        assertThat(item.coverUrl()).isNull();
        assertThat(item.releaseDate()).isNull();
    }

    @Test
    void aFullPageOfTwentyReportsMoreAvailable() {
        expectTokenRequest().andRespond(tokenResponse("token-1", 5_000_000));
        expectGamesRequest().andRespond(withSuccess(fullPageOfGamesJson(), MediaType.APPLICATION_JSON));

        var result = client.popularGames(1);

        assertThat(result.items()).hasSize(20);
        assertThat(result.hasMore()).isTrue();
    }

    @Test
    void aShortPageReportsNoMoreAvailable() {
        expectTokenRequest().andRespond(tokenResponse("token-1", 5_000_000));
        expectGamesRequest().andRespond(withSuccess("""
            [{"id": 9, "name": "Only One Left"}]
            """, MediaType.APPLICATION_JSON));

        var result = client.popularGames(1);

        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void requestsSortedByTotalRatingCountDescendingWithLimitAndOffsetComputedFromThePage() {
        expectTokenRequest().andRespond(tokenResponse("token-1", 5_000_000));
        gamesServer.expect(requestTo(GAMES_BASE_URL + "/games"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentType(MediaType.TEXT_PLAIN))
            .andExpect(content().string("""
                fields name,cover.image_id,first_release_date,total_rating;
                sort total_rating_count desc;
                limit 20;
                offset 40;
                """))
            .andRespond(emptyGamesResponse());

        client.popularGames(3);

        gamesServer.verify();
    }

    @Test
    void searchMapsAMatchingResponseIntoCatalogItemsJustLikePopularGames() {
        expectTokenRequest().andRespond(tokenResponse("token-1", 5_000_000));
        expectGamesRequest()
            .andExpect(content().string("""
                search "witcher";
                fields name,cover.image_id,first_release_date,total_rating;
                limit 20;
                offset 0;
                """))
            .andRespond(withSuccess("""
                [
                  {
                    "id": 1942,
                    "name": "The Witcher 3: Wild Hunt",
                    "cover": {"id": 89386, "image_id": "co1wyy"},
                    "first_release_date": 1431993600,
                    "total_rating": 92.5
                  }
                ]
                """, MediaType.APPLICATION_JSON));

        var result = client.search("witcher", 1);

        assertThat(result.items()).hasSize(1);
        var item = result.items().getFirst();
        assertThat(item.provider()).isEqualTo("IGDB");
        assertThat(item.mediaType()).isEqualTo("games");
        assertThat(item.title()).isEqualTo("The Witcher 3: Wild Hunt");
        gamesServer.verify();
    }

    @Test
    void searchWithNoMatchesMapsToAnEmptyPageRatherThanAnError() {
        expectTokenRequest().andRespond(tokenResponse("token-1", 5_000_000));
        expectGamesRequest().andRespond(emptyGamesResponse());

        var result = client.search("zzzznomatch", 1);

        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void searchEscapesAQuoteInTheQueryRatherThanBreakingTheApicalypseClause() {
        expectTokenRequest().andRespond(tokenResponse("token-1", 5_000_000));
        expectGamesRequest()
            .andExpect(content().string("""
                search "the \\"witcher\\"";
                fields name,cover.image_id,first_release_date,total_rating;
                limit 20;
                offset 0;
                """))
            .andRespond(emptyGamesResponse());

        client.search("the \"witcher\"", 1);

        gamesServer.verify();
    }

    @Test
    void searchStripsControlCharactersFromTheQueryRatherThanEmbeddingThemRaw() {
        expectTokenRequest().andRespond(tokenResponse("token-1", 5_000_000));
        expectGamesRequest()
            .andExpect(content().string("""
                search "witcher3";
                fields name,cover.image_id,first_release_date,total_rating;
                limit 20;
                offset 0;
                """))
            .andRespond(emptyGamesResponse());

        // A raw newline or NUL byte has no legitimate role in a title
        // search and, left unescaped, could otherwise break out of the
        // quoted Apicalypse string it's embedded in.
        client.search("witcher\n3", 1);

        gamesServer.verify();
    }

    @Test
    void searchRequestsLimitAndOffsetComputedFromThePage() {
        expectTokenRequest().andRespond(tokenResponse("token-1", 5_000_000));
        gamesServer.expect(requestTo(GAMES_BASE_URL + "/games"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().string("""
                search "witcher";
                fields name,cover.image_id,first_release_date,total_rating;
                limit 20;
                offset 40;
                """))
            .andRespond(emptyGamesResponse());

        client.search("witcher", 3);

        gamesServer.verify();
    }

    @Test
    void anUpstream5xxOnSearchIsWrappedAsACatalogUpstreamException() {
        expectTokenRequest().andRespond(tokenResponse("token-1", 5_000_000));
        expectGamesRequest().andRespond(withServerError());

        assertThatThrownBy(() -> client.search("witcher", 1)).isInstanceOf(CatalogUpstreamException.class);
    }

    @Test
    void aFourZeroOneOnSearchForcesOneTokenRefetchAndRetryJustLikePopular() {
        expectTokenRequest().andRespond(tokenResponse("stale-token", 5_000_000));
        expectGamesRequest()
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer stale-token"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectTokenRequest().andRespond(tokenResponse("fresh-token", 5_000_000));
        expectGamesRequest()
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fresh-token"))
            .andRespond(emptyGamesResponse());

        var result = client.search("witcher", 1);

        assertThat(result.items()).isEmpty();
        gamesServer.verify();
        twitchServer.verify();
    }

    @Test
    void discoverGamesRequestsTheGivenSortFieldAndDirectionWithLimitAndOffset() {
        expectTokenRequest().andRespond(tokenResponse("token-1", 5_000_000));
        gamesServer.expect(requestTo(GAMES_BASE_URL + "/games"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().string("""
                fields name,cover.image_id,first_release_date,total_rating;
                sort name asc;
                limit 20;
                offset 0;
                """))
            .andRespond(emptyGamesResponse());

        client.discoverGames("title", "asc", null, 1);

        gamesServer.verify();
    }

    @Test
    void discoverGamesMapsPopularityToTotalRatingCount() {
        assertDiscoverGamesSortField("popularity", "total_rating_count");
    }

    @Test
    void discoverGamesMapsReleaseDateToFirstReleaseDate() {
        assertDiscoverGamesSortField("release_date", "first_release_date");
    }

    @Test
    void discoverGamesMapsTitleToName() {
        assertDiscoverGamesSortField("title", "name");
    }

    @Test
    void discoverGamesMapsExternalRatingToTotalRating() {
        assertDiscoverGamesSortField("external_rating", "total_rating");
    }

    @Test
    void discoverGamesMapsAResponseIntoCatalogItemsJustLikePopularGames() {
        expectTokenRequest().andRespond(tokenResponse("token-1", 5_000_000));
        expectGamesRequest().andRespond(withSuccess("""
            [{"id": 1942, "name": "The Witcher 3: Wild Hunt", "cover": {"id": 1, "image_id": "co1wyy"}, "first_release_date": 1431993600, "total_rating": 92.5}]
            """, MediaType.APPLICATION_JSON));

        var result = client.discoverGames("external_rating", "desc", null, 1);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().title()).isEqualTo("The Witcher 3: Wild Hunt");
    }

    @Test
    void anUpstream5xxOnDiscoverGamesIsWrappedAsACatalogUpstreamException() {
        expectTokenRequest().andRespond(tokenResponse("token-1", 5_000_000));
        expectGamesRequest().andRespond(withServerError());

        assertThatThrownBy(() -> client.discoverGames("popularity", "desc", null, 1))
            .isInstanceOf(CatalogUpstreamException.class);
    }

    @Test
    void discoverGamesAddsAWhereGenresClauseWhenAGenreIsGiven() {
        expectTokenRequest().andRespond(tokenResponse("token-1", 5_000_000));
        gamesServer.expect(requestTo(GAMES_BASE_URL + "/games"))
            .andExpect(content().string("""
                fields name,cover.image_id,first_release_date,total_rating;
                where genres = (5);
                sort total_rating_count desc;
                limit 20;
                offset 0;
                """))
            .andRespond(emptyGamesResponse());

        client.discoverGames("popularity", "desc", "5", 1);

        gamesServer.verify();
    }

    @Test
    void discoverGamesOmitsTheWhereClauseEntirelyWhenNoGenreIsGiven() {
        expectTokenRequest().andRespond(tokenResponse("token-1", 5_000_000));
        gamesServer.expect(requestTo(GAMES_BASE_URL + "/games"))
            .andExpect(content().string("""
                fields name,cover.image_id,first_release_date,total_rating;
                sort total_rating_count desc;
                limit 20;
                offset 0;
                """))
            .andRespond(emptyGamesResponse());

        client.discoverGames("popularity", "desc", null, 1);

        gamesServer.verify();
    }

    @Test
    void popularGamesBuildsTheIdenticalRequestAsDiscoverGamesWithPopularityDescending() {
        // IGDB's default feed is already "sorted by popularity desc" (ADR
        // 0018's "nothing applied" row) — this pins that equivalence rather
        // than letting the two request-body builders silently drift apart.
        expectTokenRequest().andRespond(tokenResponse("token-1", 5_000_000));
        gamesServer.expect(requestTo(GAMES_BASE_URL + "/games"))
            .andExpect(content().string("""
                fields name,cover.image_id,first_release_date,total_rating;
                sort total_rating_count desc;
                limit 20;
                offset 0;
                """))
            .andRespond(emptyGamesResponse());

        client.popularGames(1);

        gamesServer.verify();
    }

    @Test
    void genresMapsTheNativeIgdbEnumIntoCatalogFilterOptions() {
        expectTokenRequest().andRespond(tokenResponse("token-1", 5_000_000));
        gamesServer.expect(requestTo(GAMES_BASE_URL + "/genres"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().string("""
                fields name;
                limit 500;
                sort name asc;
                """))
            .andRespond(withSuccess("""
                [{"id": 5, "name": "Shooter"}, {"id": 12, "name": "Role-playing (RPG)"}]
                """, MediaType.APPLICATION_JSON));

        var genres = client.genres();

        assertThat(genres).containsExactly(
            new CatalogFilterOption("5", "Shooter"),
            new CatalogFilterOption("12", "Role-playing (RPG)"));
        gamesServer.verify();
    }

    @Test
    void anEmptyGenresResponseMapsToAnEmptyListRatherThanThrowing() {
        expectTokenRequest().andRespond(tokenResponse("token-1", 5_000_000));
        gamesServer.expect(requestTo(GAMES_BASE_URL + "/genres")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.genres()).isEmpty();
    }

    @Test
    void aFourZeroOneOnGenresForcesOneTokenRefetchAndRetryJustLikeGames() {
        expectTokenRequest().andRespond(tokenResponse("stale-token", 5_000_000));
        gamesServer.expect(requestTo(GAMES_BASE_URL + "/genres"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer stale-token"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectTokenRequest().andRespond(tokenResponse("fresh-token", 5_000_000));
        gamesServer.expect(requestTo(GAMES_BASE_URL + "/genres"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fresh-token"))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.genres()).isEmpty();
        gamesServer.verify();
        twitchServer.verify();
    }

    @Test
    void anUpstream5xxOnGenresIsWrappedAsACatalogUpstreamException() {
        expectTokenRequest().andRespond(tokenResponse("token-1", 5_000_000));
        gamesServer.expect(requestTo(GAMES_BASE_URL + "/genres")).andRespond(withServerError());

        assertThatThrownBy(client::genres).isInstanceOf(CatalogUpstreamException.class);
    }

    private void assertDiscoverGamesSortField(String sortKey, String expectedField) {
        expectTokenRequest().andRespond(tokenResponse("token-1", 5_000_000));
        gamesServer.expect(requestTo(GAMES_BASE_URL + "/games"))
            .andExpect(content().string("""
                fields name,cover.image_id,first_release_date,total_rating;
                sort %s desc;
                limit 20;
                offset 0;
                """.formatted(expectedField)))
            .andRespond(emptyGamesResponse());

        client.discoverGames(sortKey, "desc", null, 1);
    }

    private org.springframework.test.web.client.ResponseActions expectTokenRequest() {
        return twitchServer.expect(requestTo(org.hamcrest.Matchers.startsWith(TWITCH_BASE_URL + "/oauth2/token")))
            .andExpect(method(HttpMethod.POST));
    }

    private org.springframework.test.web.client.ResponseActions expectGamesRequest() {
        return gamesServer.expect(requestTo(GAMES_BASE_URL + "/games")).andExpect(method(HttpMethod.POST));
    }

    private static org.springframework.test.web.client.response.DefaultResponseCreator tokenResponse(
        String accessToken, long expiresInSeconds
    ) {
        return withSuccess(
            """
            {"access_token": "%s", "expires_in": %d, "token_type": "bearer"}
            """.formatted(accessToken, expiresInSeconds),
            MediaType.APPLICATION_JSON);
    }

    private static org.springframework.test.web.client.response.DefaultResponseCreator emptyGamesResponse() {
        return withSuccess("[]", MediaType.APPLICATION_JSON);
    }

    private static String fullPageOfGamesJson() {
        var games = new StringBuilder();
        for (var i = 1; i <= 20; i++) {
            if (i > 1) {
                games.append(',');
            }
            games.append("{\"id\": ").append(i).append(", \"name\": \"Game ").append(i).append("\"}");
        }
        return "[" + games + "]";
    }
}
