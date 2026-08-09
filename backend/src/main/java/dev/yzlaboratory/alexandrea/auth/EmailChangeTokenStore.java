package dev.yzlaboratory.alexandrea.auth;

import java.time.Clock;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A single-use, expiring token that also carries a payload — the address the
 * account's email will switch to once the token is consumed. Kept separate
 * from {@link TokenService} rather than folding a payload column into
 * {@code auth_tokens}: that table serves verification/reset, neither of which
 * needs one, and reusing its kind-generic SQL for a table-specific payload
 * would leak this store's concern into a shared one.
 */
@Service
public class EmailChangeTokenStore {

    private final JdbcClient jdbcClient;
    private final Clock clock;
    private final AuthProperties properties;

    public EmailChangeTokenStore(JdbcClient jdbcClient, Clock clock, AuthProperties properties) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
        this.properties = properties;
    }

    @Transactional
    public String issue(long userId, String newEmail) {
        invalidateActive(userId);

        var rawToken = SingleUseTokens.generate();
        var now = clock.instant();
        jdbcClient
            .sql("""
                INSERT INTO pending_email_changes (user_id, new_email, token_hash, expires_at, created_at)
                VALUES (:userId, :newEmail, :tokenHash, :expiresAt, :createdAt)
                """)
            .param("userId", userId)
            .param("newEmail", newEmail)
            .param("tokenHash", SingleUseTokens.hash(rawToken))
            .param("expiresAt", now.plus(properties.emailChangeTokenTtl()).toString())
            .param("createdAt", now.toString())
            .update();
        return rawToken;
    }

    /**
     * The burn is a conditional update guarded on {@code consumed_at IS NULL},
     * mirroring {@link TokenService#consume}'s race-safety: only the update
     * that flips the row wins.
     */
    @Transactional
    public EmailChangeConsumption consume(String rawToken) {
        var match = jdbcClient
            .sql("""
                SELECT id, user_id, new_email, expires_at FROM pending_email_changes
                WHERE token_hash = :tokenHash AND consumed_at IS NULL
                """)
            .param("tokenHash", SingleUseTokens.hash(rawToken))
            .query((rs, rowNum) -> new LivePendingChange(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("new_email"),
                Instant.parse(rs.getString("expires_at"))))
            .optional();

        if (match.isEmpty()) {
            return new EmailChangeConsumption.Rejected();
        }
        var pending = match.get();
        if (!clock.instant().isBefore(pending.expiresAt())) {
            return new EmailChangeConsumption.Expired();
        }

        var burned = jdbcClient
            .sql("UPDATE pending_email_changes SET consumed_at = :now WHERE id = :id AND consumed_at IS NULL")
            .param("now", clock.instant().toString())
            .param("id", pending.id())
            .update();
        if (burned == 0) {
            return new EmailChangeConsumption.Rejected();
        }
        return new EmailChangeConsumption.Consumed(pending.userId(), pending.newEmail());
    }

    // Consumed rows only exist to free the partial-unique index (see
    // invalidateActive below); nothing reads them back, so the cleanup sweep
    // takes them too, alongside rows that simply outlived their TTL.
    public int deleteExpiredOrConsumed() {
        return jdbcClient
            .sql("DELETE FROM pending_email_changes WHERE expires_at <= :now OR consumed_at IS NOT NULL")
            .param("now", clock.instant().toString())
            .update();
    }

    private void invalidateActive(long userId) {
        jdbcClient
            .sql("""
                UPDATE pending_email_changes SET consumed_at = :now
                WHERE user_id = :userId AND consumed_at IS NULL
                """)
            .param("now", clock.instant().toString())
            .param("userId", userId)
            .update();
    }

    private record LivePendingChange(long id, long userId, String newEmail, Instant expiresAt) {}
}
