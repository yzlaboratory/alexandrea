package dev.yzlaboratory.alexandrea.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The pending-email-change token's lifecycle guarantees, exercised against a
 * real Flyway-migrated SQLite so the partial-unique-index behaviour is the
 * production one — mirrors {@link TokenServiceTest}'s coverage for the
 * payload-carrying sibling store.
 */
class EmailChangeTokenStoreTest {

    private static final Instant START = Instant.parse("2026-06-07T12:00:00Z");

    private MigratedSqlite db;
    private MutableClock clock;
    private EmailChangeTokenStore store;
    private long userId;

    @BeforeEach
    void setUp() {
        db = MigratedSqlite.create();
        clock = new MutableClock(START);
        store = new EmailChangeTokenStore(db.jdbcClient(), clock, defaultProperties());
        userId = insertUser(db.jdbcClient(), "owner@example.com");
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void consumingAFreshTokenReturnsTheOwningUserAndTheNewEmail() {
        var rawToken = store.issue(userId, "new@example.com");

        var outcome = store.consume(rawToken);

        assertThat(outcome).isEqualTo(new EmailChangeConsumption.Consumed(userId, "new@example.com"));
    }

    @Test
    void issuedTokenIsUrlSafeAndCarriesAtLeast128Bits() {
        var rawToken = store.issue(userId, "new@example.com");

        assertThat(rawToken).matches("[A-Za-z0-9_-]+");
        assertThat(rawToken).hasSize(22);
    }

    @Test
    void aTokenCannotBeConsumedTwice() {
        var rawToken = store.issue(userId, "new@example.com");
        store.consume(rawToken);

        var secondAttempt = store.consume(rawToken);

        assertThat(secondAttempt).isInstanceOf(EmailChangeConsumption.Rejected.class);
    }

    @Test
    void anExpiredTokenIsRejectedAsExpired() {
        var rawToken = store.issue(userId, "new@example.com");
        clock.advance(Duration.ofHours(24).plusSeconds(1));

        var outcome = store.consume(rawToken);

        assertThat(outcome).isInstanceOf(EmailChangeConsumption.Expired.class);
    }

    @Test
    void aTokenIsStillValidOneSecondBeforeExpiry() {
        var rawToken = store.issue(userId, "new@example.com");
        clock.advance(Duration.ofHours(24).minusSeconds(1));

        var outcome = store.consume(rawToken);

        assertThat(outcome).isEqualTo(new EmailChangeConsumption.Consumed(userId, "new@example.com"));
    }

    @Test
    void issuingASecondTokenForTheSameUserInvalidatesTheFirstEvenWithADifferentTarget() {
        var firstToken = store.issue(userId, "first-choice@example.com");
        var secondToken = store.issue(userId, "second-choice@example.com");

        assertThat(store.consume(firstToken)).isInstanceOf(EmailChangeConsumption.Rejected.class);
        assertThat(store.consume(secondToken))
            .isEqualTo(new EmailChangeConsumption.Consumed(userId, "second-choice@example.com"));
    }

    @Test
    void anUnknownTokenIsRejected() {
        var outcome = store.consume("not-a-real-token");

        assertThat(outcome).isInstanceOf(EmailChangeConsumption.Rejected.class);
    }

    @Test
    void issuingATokenForOneUserDoesNotInvalidateAnotherUsersOutstandingToken() {
        var otherUserId = insertUser(db.jdbcClient(), "someone-else@example.com");
        var otherUsersToken = store.issue(otherUserId, "someone-elses-new@example.com");

        store.issue(userId, "new@example.com");

        assertThat(store.consume(otherUsersToken))
            .isEqualTo(new EmailChangeConsumption.Consumed(otherUserId, "someone-elses-new@example.com"));
    }

    @Test
    void sweepingWithNothingToCleanIsANoOp() {
        assertThat(store.deleteExpiredOrConsumed()).isZero();
    }

    @Test
    void anExpiredUnconsumedTokenIsRemovedBySweep() {
        store.issue(userId, "new@example.com");
        clock.advance(Duration.ofHours(24).plusSeconds(1));

        assertThat(store.deleteExpiredOrConsumed()).isOne();
        assertThat(pendingChangeRowCount()).isZero();
    }

    @Test
    void aConsumedTokenIsRemovedBySweepEvenBeforeItExpires() {
        var rawToken = store.issue(userId, "new@example.com");
        store.consume(rawToken);

        assertThat(store.deleteExpiredOrConsumed()).isOne();
        assertThat(pendingChangeRowCount()).isZero();
    }

    @Test
    void aLiveUnexpiredUnconsumedTokenSurvivesSweep() {
        store.issue(userId, "new@example.com");

        assertThat(store.deleteExpiredOrConsumed()).isZero();
        assertThat(pendingChangeRowCount()).isOne();
    }

    private int pendingChangeRowCount() {
        return db.jdbcClient().sql("SELECT COUNT(*) FROM pending_email_changes").query(Integer.class).single();
    }

    private static AuthProperties defaultProperties() {
        return new AuthProperties(
            null, null, null, null,
            Duration.ofHours(24), "http://localhost/confirm-email-change?token={token}",
            null, null, null);
    }

    private static long insertUser(JdbcClient jdbcClient, String email) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
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
