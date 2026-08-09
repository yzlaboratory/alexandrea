package dev.yzlaboratory.alexandrea.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * The sweep's cross-table orchestration, exercised against a real
 * Flyway-migrated SQLite so all three deletes run together — each store's
 * own boundary conditions are already covered by its own test.
 */
class AuthCleanupSchedulerTest {

    private static final Instant START = Instant.parse("2026-06-07T12:00:00Z");

    private MigratedSqlite db;
    private MutableClock clock;
    private TokenService tokenService;
    private EmailChangeTokenStore emailChangeTokenStore;
    private RateLimitBucketStore rateLimitBucketStore;
    private AuthCleanupScheduler scheduler;
    private long userId;

    @BeforeEach
    void setUp() {
        db = MigratedSqlite.create();
        clock = new MutableClock(START);
        var properties = new AuthProperties(
            Duration.ofHours(24), null,
            Duration.ofHours(1), null,
            Duration.ofHours(24), null,
            new AuthProperties.RateLimit(10, Duration.ofMinutes(15)),
            new AuthProperties.RateLimit(5, Duration.ofHours(1)),
            null);
        tokenService = new TokenService(db.jdbcClient(), clock, properties);
        emailChangeTokenStore = new EmailChangeTokenStore(db.jdbcClient(), clock, properties);
        rateLimitBucketStore = new RateLimitBucketStore(db.jdbcClient(), clock, properties);
        scheduler = new AuthCleanupScheduler(tokenService, emailChangeTokenStore, rateLimitBucketStore, properties);
        userId = insertUser(db.jdbcClient(), "owner@example.com");
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void sweepingWithNothingToCleanIsANoOp() {
        scheduler.sweep();

        assertThat(tokenCount()).isZero();
        assertThat(pendingEmailChangeCount()).isZero();
        assertThat(bucketCount()).isZero();
    }

    @Test
    void sweepRemovesExpiredRowsAcrossAllThreeTables() {
        tokenService.issue(TokenKind.VERIFICATION, userId);
        emailChangeTokenStore.issue(userId, "new@example.com");
        rateLimitBucketStore.recordAttempt("login:ip:1.2.3.4", Duration.ofMinutes(15));
        // Past every configured TTL and rate-limit window at once.
        clock.advance(Duration.ofHours(24).plusMinutes(1));

        scheduler.sweep();

        assertThat(tokenCount()).isZero();
        assertThat(pendingEmailChangeCount()).isZero();
        assertThat(bucketCount()).isZero();
    }

    @Test
    void sweepLeavesStillLiveRowsInAllThreeTablesUntouched() {
        tokenService.issue(TokenKind.VERIFICATION, userId);
        emailChangeTokenStore.issue(userId, "new@example.com");
        rateLimitBucketStore.recordAttempt("login:ip:1.2.3.4", Duration.ofMinutes(15));

        scheduler.sweep();

        assertThat(tokenCount()).isOne();
        assertThat(pendingEmailChangeCount()).isOne();
        assertThat(bucketCount()).isOne();
    }

    private int tokenCount() {
        return db.jdbcClient().sql("SELECT COUNT(*) FROM auth_tokens").query(Integer.class).single();
    }

    private int pendingEmailChangeCount() {
        return db.jdbcClient().sql("SELECT COUNT(*) FROM pending_email_changes").query(Integer.class).single();
    }

    private int bucketCount() {
        return db.jdbcClient().sql("SELECT COUNT(*) FROM rate_limit_buckets").query(Integer.class).single();
    }

    private static long insertUser(JdbcClient jdbcClient, String email) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcClient
            .sql("""
                INSERT INTO users (email, password_hash, verified, created_at, updated_at)
                VALUES (:email, 'hash', 0, :now, :now)
                """)
            .param("email", email)
            .param("now", START.toString())
            .update(keyHolder);
        return keyHolder.getKey().longValue();
    }
}
