package dev.yzlaboratory.alexandrea.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yzlaboratory.alexandrea.auth.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The breaker's state machine from ADR 0015, exercised in isolation with a
 * clock the test controls — the equivalent of {@code RateLimiterTest}'s
 * {@code MutableClock} usage, but for in-process state rather than a SQLite
 * bucket table.
 */
class ProviderCircuitBreakerTest {

    private static final String TMDB = "TMDB";
    private static final String OPEN_LIBRARY = "OpenLibrary";
    private static final Instant START = Instant.parse("2026-06-07T12:00:00Z");
    private static final Duration OPEN_WINDOW = Duration.ofSeconds(60);

    private MutableClock clock;
    private ProviderCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        breaker = new ProviderCircuitBreaker(clock);
    }

    @Test
    void aProviderNeverSeenBeforeIsClosed() {
        assertThat(breaker.allowRequest(TMDB)).isTrue();
    }

    @Test
    void staysClosedAfterFewerThanFiveConsecutiveFailures() {
        failFourTimes();

        assertThat(breaker.allowRequest(TMDB)).isTrue();
    }

    @Test
    void opensOnTheFifthConsecutiveFailure() {
        failFiveTimes();

        assertThat(breaker.allowRequest(TMDB)).isFalse();
    }

    @Test
    void aSuccessBeforeTheThresholdResetsTheConsecutiveCount() {
        failFourTimes();
        breaker.recordSuccess(TMDB);
        failFourTimes();

        // 8 total failures, but never 5 in a row thanks to the success in between.
        assertThat(breaker.allowRequest(TMDB)).isTrue();
    }

    @Test
    void shortCircuitsForTheWholeOpenWindow() {
        failFiveTimes();

        clock.advance(OPEN_WINDOW.minusSeconds(1));

        assertThat(breaker.allowRequest(TMDB)).isFalse();
    }

    @Test
    void halfOpensOnceTheOpenWindowElapsesAndAdmitsAProbe() {
        failFiveTimes();

        clock.advance(OPEN_WINDOW.plusSeconds(1));

        assertThat(breaker.allowRequest(TMDB)).isTrue();
    }

    @Test
    void onlyOneProbeIsAdmittedPerHalfOpenWindow() {
        failFiveTimes();
        clock.advance(OPEN_WINDOW.plusSeconds(1));
        assertThat(breaker.allowRequest(TMDB)).isTrue();

        assertThat(breaker.allowRequest(TMDB)).isFalse();
    }

    @Test
    void aSuccessfulProbeClosesTheBreaker() {
        failFiveTimes();
        clock.advance(OPEN_WINDOW.plusSeconds(1));
        assertThat(breaker.allowRequest(TMDB)).isTrue();

        breaker.recordSuccess(TMDB);

        assertThat(breaker.allowRequest(TMDB)).isTrue();
    }

    @Test
    void afterTheBreakerClosesItTakesAFreshFiveFailuresToReopen() {
        failFiveTimes();
        clock.advance(OPEN_WINDOW.plusSeconds(1));
        breaker.allowRequest(TMDB); // claims and consumes the probe
        breaker.recordSuccess(TMDB);

        failFourTimes();
        assertThat(breaker.allowRequest(TMDB)).isTrue();

        breaker.recordFailure(TMDB);
        assertThat(breaker.allowRequest(TMDB)).isFalse();
    }

    @Test
    void aFailedProbeReopensTheBreakerImmediately() {
        failFiveTimes();
        clock.advance(OPEN_WINDOW.plusSeconds(1));
        breaker.allowRequest(TMDB); // claims the probe

        breaker.recordFailure(TMDB);

        assertThat(breaker.allowRequest(TMDB)).isFalse();
    }

    @Test
    void aFailedProbeStartsAFreshSixtySecondWindowFromTheFailure() {
        failFiveTimes();
        clock.advance(OPEN_WINDOW.plusSeconds(1));
        breaker.allowRequest(TMDB);
        breaker.recordFailure(TMDB);

        clock.advance(OPEN_WINDOW.minusSeconds(1));
        assertThat(breaker.allowRequest(TMDB)).isFalse();

        clock.advance(Duration.ofSeconds(2));
        assertThat(breaker.allowRequest(TMDB)).isTrue();
    }

    @Test
    void oneProvidersFailuresDoNotAffectAnothers() {
        failFiveTimes();

        assertThat(breaker.allowRequest(TMDB)).isFalse();
        assertThat(breaker.allowRequest(OPEN_LIBRARY)).isTrue();
    }

    @Test
    void eachProviderTracksItsOwnFailureCount() {
        breaker.recordFailure(TMDB);
        breaker.recordFailure(TMDB);
        for (var i = 0; i < 5; i++) {
            breaker.recordFailure(OPEN_LIBRARY);
        }

        assertThat(breaker.allowRequest(TMDB)).isTrue();
        assertThat(breaker.allowRequest(OPEN_LIBRARY)).isFalse();
    }

    private void failFourTimes() {
        for (var i = 0; i < 4; i++) {
            breaker.recordFailure(TMDB);
        }
    }

    private void failFiveTimes() {
        for (var i = 0; i < 5; i++) {
            breaker.recordFailure(TMDB);
        }
    }
}
