package dev.yzlaboratory.alexandrea.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The bucket store's staleness rule, exercised against a real
 * Flyway-migrated SQLite so the delete's boundary condition is the
 * production one.
 */
class RateLimitBucketStoreTest {

    private static final Instant START = Instant.parse("2026-06-07T12:00:00Z");
    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);
    private static final Duration MAIL_WINDOW = Duration.ofHours(1);

    private MigratedSqlite db;
    private MutableClock clock;
    private RateLimitBucketStore store;

    @BeforeEach
    void setUp() {
        db = MigratedSqlite.create();
        clock = new MutableClock(START);
        var properties = new AuthProperties(
            null, null, null, null, null, null,
            new AuthProperties.RateLimit(10, LOGIN_WINDOW),
            new AuthProperties.RateLimit(5, MAIL_WINDOW));
        store = new RateLimitBucketStore(db.jdbcClient(), clock, properties);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void sweepingAnEmptyTableIsANoOp() {
        assertThat(store.deleteStale()).isZero();
    }

    @Test
    void aBucketOlderThanTheLongestConfiguredWindowIsRemoved() {
        store.recordAttempt("login:ip:1.2.3.4", LOGIN_WINDOW);
        clock.advance(MAIL_WINDOW.plusSeconds(1));

        assertThat(store.deleteStale()).isOne();
        assertThat(bucketCount()).isZero();
    }

    @Test
    void aBucketPastItsOwnScopesWindowButWithinTheLongestOneSurvivesSweep() {
        store.recordAttempt("login:ip:1.2.3.4", LOGIN_WINDOW);
        // Past the 15m login window, but still inside the longer 1h mail window.
        clock.advance(LOGIN_WINDOW.plusMinutes(1));

        assertThat(store.deleteStale()).isZero();
        assertThat(bucketCount()).isOne();
    }

    @Test
    void theCurrentlyActiveWindowSurvivesSweep() {
        store.recordAttempt("mail:ip:1.2.3.4", MAIL_WINDOW);

        assertThat(store.deleteStale()).isZero();
        assertThat(bucketCount()).isOne();
    }

    private int bucketCount() {
        return db.jdbcClient().sql("SELECT COUNT(*) FROM rate_limit_buckets").query(Integer.class).single();
    }
}
