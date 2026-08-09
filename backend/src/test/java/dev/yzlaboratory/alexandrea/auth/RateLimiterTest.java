package dev.yzlaboratory.alexandrea.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The rate limiter's window and dual-keying guarantees, exercised against a
 * real Flyway-migrated SQLite so the UPSERT's window-rollover behaviour is the
 * production one.
 */
class RateLimiterTest {

    private static final Instant START = Instant.parse("2026-06-07T12:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_ATTEMPTS = 3;

    private MigratedSqlite db;
    private MutableClock clock;
    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        db = MigratedSqlite.create();
        clock = new MutableClock(START);
        var properties = new AuthProperties(
            null, null, null, null, null, null,
            new AuthProperties.RateLimit(MAX_ATTEMPTS, WINDOW),
            new AuthProperties.RateLimit(MAX_ATTEMPTS, WINDOW));
        rateLimiter = new RateLimiter(new RateLimitBucketStore(db.jdbcClient(), clock), properties);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void allowsRequestsUpToTheConfiguredThreshold() {
        for (var i = 0; i < MAX_ATTEMPTS; i++) {
            assertThat(rateLimiter.allowLogin("1.2.3.4", "owner@example.com")).isTrue();
        }
    }

    @Test
    void deniesTheRequestThatExceedsTheThreshold() {
        exhaustLoginBudget("1.2.3.4", "owner@example.com");

        assertThat(rateLimiter.allowLogin("1.2.3.4", "owner@example.com")).isFalse();
    }

    @Test
    void aTrippedIpBucketDeniesEvenAFreshEmail() {
        exhaustLoginBudget("1.2.3.4", "owner@example.com");

        assertThat(rateLimiter.allowLogin("1.2.3.4", "brand-new@example.com")).isFalse();
    }

    @Test
    void aTrippedEmailBucketDeniesEvenAFreshIp() {
        exhaustLoginBudget("1.2.3.4", "owner@example.com");

        assertThat(rateLimiter.allowLogin("9.9.9.9", "owner@example.com")).isFalse();
    }

    @Test
    void aFreshIpAndFreshEmailTogetherAreUnaffectedByAnotherPairsThrottling() {
        exhaustLoginBudget("1.2.3.4", "owner@example.com");

        assertThat(rateLimiter.allowLogin("9.9.9.9", "brand-new@example.com")).isTrue();
    }

    @Test
    void loginAndMailScopesTrackIndependentBuckets() {
        exhaustLoginBudget("1.2.3.4", "owner@example.com");

        assertThat(rateLimiter.allowMailAction("1.2.3.4", "owner@example.com")).isTrue();
    }

    @Test
    void emailKeyingIsCaseAndWhitespaceInsensitive() {
        for (var i = 0; i < MAX_ATTEMPTS; i++) {
            rateLimiter.allowLogin("1.2.3.4", " Owner@Example.com ");
        }

        assertThat(rateLimiter.allowLogin("5.6.7.8", "owner@example.com")).isFalse();
    }

    @Test
    void aFreshWindowResetsTheCount() {
        exhaustLoginBudget("1.2.3.4", "owner@example.com");
        assertThat(rateLimiter.allowLogin("1.2.3.4", "owner@example.com")).isFalse();

        clock.advance(WINDOW.plusSeconds(1));

        assertThat(rateLimiter.allowLogin("1.2.3.4", "owner@example.com")).isTrue();
    }

    @Test
    void aRequestOneSecondBeforeTheWindowRollsOverIsStillThrottled() {
        exhaustLoginBudget("1.2.3.4", "owner@example.com");
        clock.advance(WINDOW.minusSeconds(1));

        assertThat(rateLimiter.allowLogin("1.2.3.4", "owner@example.com")).isFalse();
    }

    private void exhaustLoginBudget(String clientIp, String email) {
        for (var i = 0; i < MAX_ATTEMPTS; i++) {
            rateLimiter.allowLogin(clientIp, email);
        }
    }
}
