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

    public RateLimitBucketStore(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
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
}
