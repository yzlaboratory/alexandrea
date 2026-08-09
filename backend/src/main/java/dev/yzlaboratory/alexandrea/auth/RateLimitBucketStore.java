package dev.yzlaboratory.alexandrea.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RateLimitBucketStore {

    private final JdbcClient jdbcClient;
    private final Clock clock;
    private final AuthProperties properties;

    public RateLimitBucketStore(JdbcClient jdbcClient, Clock clock, AuthProperties properties) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * A single UPSERT so two requests racing the same key can't interleave a
     * read-then-write gap and both land as attempt 1.
     */
    public int recordAttempt(String bucketKey, Duration window) {
        var windowStart = currentWindowStart(window).toString();
        return jdbcClient
            .sql("""
                INSERT INTO rate_limit_buckets (bucket_key, window_started_at, attempt_count)
                VALUES (:bucketKey, :windowStart, 1)
                ON CONFLICT(bucket_key) DO UPDATE SET
                    attempt_count = CASE
                        WHEN window_started_at = :windowStart THEN attempt_count + 1
                        ELSE 1
                    END,
                    window_started_at = :windowStart
                RETURNING attempt_count
                """)
            .param("bucketKey", bucketKey)
            .param("windowStart", windowStart)
            .query(Integer.class)
            .single();
    }

    private Instant currentWindowStart(Duration window) {
        var windowSeconds = window.getSeconds();
        var now = clock.instant().getEpochSecond();
        return Instant.ofEpochSecond(Math.floorDiv(now, windowSeconds) * windowSeconds);
    }

    /**
     * This table doesn't record which scope's window produced a row, so a row
     * is only provably inert once it has outlived the longest configured
     * window. A row can survive a little past its own, shorter scope's
     * window — harmless, since the next attempt for that key rolls it over
     * regardless.
     */
    public int deleteStale() {
        var cutoff = clock.instant().minus(longestWindow());
        return jdbcClient
            .sql("DELETE FROM rate_limit_buckets WHERE window_started_at < :cutoff")
            .param("cutoff", cutoff.toString())
            .update();
    }

    private Duration longestWindow() {
        var loginWindow = properties.loginRateLimit().window();
        var mailWindow = properties.mailRateLimit().window();
        return loginWindow.compareTo(mailWindow) >= 0 ? loginWindow : mailWindow;
    }
}
