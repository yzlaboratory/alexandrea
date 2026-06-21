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
 * Issues and consumes the single-use, expiring tokens behind every email link
 * (ADR 0021).
 *
 * <p>One deep module owns the whole token lifecycle so callers learn two verbs —
 * {@link #issue} and {@link #consume} — and get the security guarantees for
 * free: 128-bit URL-safe CSPRNG tokens (ADR 0014), single use, one active token
 * per {@code (user, kind)} (issuing invalidates the prior), and per-kind expiry.
 * The raw token is returned to the caller exactly once and is never stored; only
 * its SHA-256 hash is persisted, so a database leak yields no usable links.
 */
@Service
public class TokenService {

    /** 128 bits per ADR 0014. URL-safe Base64 of 16 random bytes. */
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

    /**
     * Issues a fresh token of {@code kind} for {@code userId}, invalidating any
     * outstanding live token of the same kind first (one active per user+kind).
     * Returns the raw token — the only time it exists in plaintext; persist
     * nothing of it beyond what the caller mails out.
     */
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
     * Validates and burns a presented token. The burn is a conditional update
     * guarded on {@code consumed_at IS NULL}, so two requests racing on the same
     * token cannot both redeem it — only the update that flips the row wins.
     * Expiry and "no live match" are reported as distinct {@link TokenConsumption}
     * variants; an expired-but-unconsumed row is left for the cleanup job, never
     * silently honoured.
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

    /** A still-redeemable token row, narrowed to what {@link #consume} needs. */
    private record LiveToken(long id, long userId, Instant expiresAt) {}
}
