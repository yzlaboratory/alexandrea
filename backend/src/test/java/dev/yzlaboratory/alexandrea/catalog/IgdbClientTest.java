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
