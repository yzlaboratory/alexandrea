package dev.yzlaboratory.alexandrea.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Only the SHA-256 hash of a token is persisted — the raw value is returned to
 * the caller once and never stored — so a database leak yields no usable links.
 */
@Service
public class TokenService {

    /** 128 bits (ADR 0014). */
    private static final int TOKEN_BYTES = 16;

    private final JdbcClient jdbcClient;
    private final Clock clock;
    private final AuthProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Base64.Encoder urlEncoder = Base64.getUrlEncoder().withoutPadding();

    public TokenService(JdbcClient jdbcClient, Clock clock, AuthProperties properties) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
        this.properties = properties;
    }

    /** The returned raw token is the only time it exists in plaintext — persist nothing of it beyond what is mailed. */
    @Transactional
    public String issue(TokenKind kind, long userId) {
        invalidateActive(kind, userId);

        var rawToken = generateRawToken();
        var now = clock.instant();
        jdbcClient
            .sql("""
                INSERT INTO auth_tokens (user_id, kind, token_hash, expires_at, created_at)
                VALUES (:userId, :kind, :tokenHash, :expiresAt, :createdAt)
                """)
            .param("userId", userId)
            .param("kind", kind.storageValue())
            .param("tokenHash", hash(rawToken))
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
            .param("tokenHash", hash(rawToken))
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

    private void invalidateActive(TokenKind kind, long userId) {
        // Mark the prior live token consumed rather than deleting it, so the
        // partial unique index frees up while the audit row survives.
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
        };
    }

    private String generateRawToken() {
        var bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return urlEncoder.encodeToString(bytes);
    }

    private static String hash(String rawToken) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated on every JVM; its absence is unrecoverable.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record LiveToken(long id, long userId, Instant expiresAt) {}
}
