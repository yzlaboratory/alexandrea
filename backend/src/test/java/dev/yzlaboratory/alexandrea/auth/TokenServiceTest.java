package dev.yzlaboratory.alexandrea.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The Token service's lifecycle guarantees, exercised against a real
 * Flyway-migrated SQLite so the partial-unique-index and SQL behaviour are the
 * production ones.
 */
class TokenServiceTest {

    private static final Instant START = Instant.parse("2026-06-07T12:00:00Z");

    private MigratedSqlite db;
    private MutableClock clock;
    private TokenService tokenService;
    private long userId;

    @BeforeEach
    void setUp() {
        db = MigratedSqlite.create();
        clock = new MutableClock(START);
        tokenService = new TokenService(db.jdbcClient(), clock, defaultProperties());
        userId = insertUser(db.jdbcClient(), "owner@example.com");
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void consumingAFreshTokenReturnsTheOwningUser() {
        var rawToken = tokenService.issue(TokenKind.VERIFICATION, userId);

        var outcome = tokenService.consume(TokenKind.VERIFICATION, rawToken);

        assertThat(outcome).isEqualTo(new TokenConsumption.Consumed(userId));
    }

    @Test
    void issuedTokenIsUrlSafeAndCarriesAtLeast128Bits() {
        var rawToken = tokenService.issue(TokenKind.VERIFICATION, userId);

        assertThat(rawToken).matches("[A-Za-z0-9_-]+");
        // 16 random bytes Base64url-without-padding encode to 22 characters.
        assertThat(rawToken).hasSize(22);
    }

    @Test
    void aTokenCannotBeConsumedTwice() {
        var rawToken = tokenService.issue(TokenKind.VERIFICATION, userId);
        tokenService.consume(TokenKind.VERIFICATION, rawToken);

        var secondAttempt = tokenService.consume(TokenKind.VERIFICATION, rawToken);

        assertThat(secondAttempt).isInstanceOf(TokenConsumption.Rejected.class);
    }

    @Test
    void anExpiredTokenIsRejectedAsExpired() {
        var rawToken = tokenService.issue(TokenKind.VERIFICATION, userId);
        clock.advance(Duration.ofHours(24).plusSeconds(1));

        var outcome = tokenService.consume(TokenKind.VERIFICATION, rawToken);

        assertThat(outcome).isInstanceOf(TokenConsumption.Expired.class);
    }

    @Test
    void aTokenIsStillValidOneSecondBeforeExpiry() {
        var rawToken = tokenService.issue(TokenKind.VERIFICATION, userId);
        clock.advance(Duration.ofHours(24).minusSeconds(1));

        var outcome = tokenService.consume(TokenKind.VERIFICATION, rawToken);

        assertThat(outcome).isEqualTo(new TokenConsumption.Consumed(userId));
    }

    @Test
    void issuingASecondTokenInvalidatesTheFirst() {
        var firstToken = tokenService.issue(TokenKind.VERIFICATION, userId);
        var secondToken = tokenService.issue(TokenKind.VERIFICATION, userId);

        assertThat(tokenService.consume(TokenKind.VERIFICATION, firstToken))
            .isInstanceOf(TokenConsumption.Rejected.class);
        assertThat(tokenService.consume(TokenKind.VERIFICATION, secondToken))
            .isEqualTo(new TokenConsumption.Consumed(userId));
    }

    @Test
    void anUnknownTokenIsRejected() {
        var outcome = tokenService.consume(TokenKind.VERIFICATION, "not-a-real-token");

        assertThat(outcome).isInstanceOf(TokenConsumption.Rejected.class);
    }

    @Test
    void aResetTokenExpiresAfterOneHourRatherThanTwentyFourHours() {
        var rawToken = tokenService.issue(TokenKind.RESET, userId);
        clock.advance(Duration.ofHours(1).plusSeconds(1));

        var outcome = tokenService.consume(TokenKind.RESET, rawToken);

        assertThat(outcome).isInstanceOf(TokenConsumption.Expired.class);
    }

    @Test
    void issuingAResetTokenDoesNotInvalidateAnOutstandingVerificationTokenForTheSameUser() {
        var verificationToken = tokenService.issue(TokenKind.VERIFICATION, userId);

        tokenService.issue(TokenKind.RESET, userId);

        assertThat(tokenService.consume(TokenKind.VERIFICATION, verificationToken))
            .isEqualTo(new TokenConsumption.Consumed(userId));
    }

    @Test
    void sweepingWithNothingToCleanIsANoOp() {
        assertThat(tokenService.deleteExpiredOrConsumed()).isZero();
    }

    @Test
    void anExpiredUnconsumedTokenIsRemovedBySweep() {
        tokenService.issue(TokenKind.VERIFICATION, userId);
        clock.advance(Duration.ofHours(24).plusSeconds(1));

        assertThat(tokenService.deleteExpiredOrConsumed()).isOne();
        assertThat(tokenRowCount()).isZero();
    }

    @Test
    void aConsumedTokenIsRemovedBySweepEvenBeforeItExpires() {
        var rawToken = tokenService.issue(TokenKind.VERIFICATION, userId);
        tokenService.consume(TokenKind.VERIFICATION, rawToken);

        assertThat(tokenService.deleteExpiredOrConsumed()).isOne();
        assertThat(tokenRowCount()).isZero();
    }

    @Test
    void aLiveUnexpiredUnconsumedTokenSurvivesSweep() {
        tokenService.issue(TokenKind.VERIFICATION, userId);

        assertThat(tokenService.deleteExpiredOrConsumed()).isZero();
        assertThat(tokenRowCount()).isOne();
    }

    private int tokenRowCount() {
        return db.jdbcClient().sql("SELECT COUNT(*) FROM auth_tokens").query(Integer.class).single();
    }

    private static AuthProperties defaultProperties() {
        return new AuthProperties(
            Duration.ofHours(24), "http://localhost/verify?token={token}",
            Duration.ofHours(1), "http://localhost/reset-password?token={token}",
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
