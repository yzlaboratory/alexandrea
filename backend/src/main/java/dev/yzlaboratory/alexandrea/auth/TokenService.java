package dev.yzlaboratory.alexandrea.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TokenService {

    private final JdbcClient jdbcClient;
    private final Clock clock;
    private final AuthProperties properties;

    public TokenService(JdbcClient jdbcClient, Clock clock, AuthProperties properties) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
        this.properties = properties;
    }

    /** The returned raw token is the only time it exists in plaintext — persist nothing of it beyond what is mailed. */
    @Transactional
    public String issue(TokenKind kind, long userId) {
        invalidateActive(kind, userId);

        var rawToken = SingleUseTokens.generate();
        var now = clock.instant();
        jdbcClient
            .sql("""
                INSERT INTO auth_tokens (user_id, kind, token_hash, expires_at, created_at)
                VALUES (:userId, :kind, :tokenHash, :expiresAt, :createdAt)
                """)
            .param("userId", userId)
            .param("kind", kind.storageValue())
            .param("tokenHash", SingleUseTokens.hash(rawToken))
            .param("expiresAt", now.plus(ttlFor(kind)).toString())
            .param("createdAt", now.toString())
            .update();
        return rawToken;
    }

    /**
     * The burn is a conditional update guarded on {@code consumed_at IS NULL}, so
     * two requests racing the same token cannot both redeem it — only the update
     * that flips the row wins. An expired-but-unconsumed row is left for the
     * cleanup job, never silently honoured.
     */
    @Transactional
    public TokenConsumption consume(TokenKind kind, String rawToken) {
        var match = jdbcClient
            .sql("""
                SELECT id, user_id, expires_at FROM auth_tokens
                WHERE kind = :kind AND token_hash = :tokenHash AND consumed_at IS NULL
                """)
            .param("kind", kind.storageValue())
            .param("tokenHash", SingleUseTokens.hash(rawToken))
            .query((rs, rowNum) -> new LiveToken(
                rs.getLong("id"),
                rs.getLong("user_id"),
                Instant.parse(rs.getString("expires_at"))))
            .optional();

        if (match.isEmpty()) {
            return new TokenConsumption.Rejected();
        }
        var token = match.get();
        if (!clock.instant().isBefore(token.expiresAt())) {
            return new TokenConsumption.Expired();
        }

        var burned = jdbcClient
            .sql("UPDATE auth_tokens SET consumed_at = :now WHERE id = :id AND consumed_at IS NULL")
            .param("now", clock.instant().toString())
            .param("id", token.id())
            .update();
        if (burned == 0) {
            return new TokenConsumption.Rejected();
        }
        return new TokenConsumption.Consumed(token.userId());
    }

    // Consumed rows only exist to free the partial-unique index (see
    // invalidateActive below); nothing reads them back, so the cleanup sweep
    // takes them too, alongside rows that simply outlived their TTL.
    public int deleteExpiredOrConsumed() {
        return jdbcClient
            .sql("DELETE FROM auth_tokens WHERE expires_at <= :now OR consumed_at IS NOT NULL")
            .param("now", clock.instant().toString())
            .update();
    }

    private void invalidateActive(TokenKind kind, long userId) {
        // Marks the prior live token consumed rather than deleting it — a
        // plain UPDATE frees the partial unique index without a DELETE; the
        // row itself is transient, cleared later by deleteExpiredOrConsumed.
        jdbcClient
            .sql("""
                UPDATE auth_tokens SET consumed_at = :now
                WHERE user_id = :userId AND kind = :kind AND consumed_at IS NULL
                """)
            .param("now", clock.instant().toString())
            .param("userId", userId)
            .param("kind", kind.storageValue())
            .update();
    }

    private Duration ttlFor(TokenKind kind) {
        return switch (kind) {
            case VERIFICATION -> properties.verificationTokenTtl();
            case RESET -> properties.resetTokenTtl();
        };
    }

    private record LiveToken(long id, long userId, Instant expiresAt) {}
}
