package dev.yzlaboratory.alexandrea.auth;

import org.springframework.stereotype.Service;

/**
 * Enforces ADR 0024's dual keying: every guarded request is counted against
 * both the client IP and the target email, and whichever bucket trips first
 * throttles it — closing both the one-host-many-emails and the
 * many-hosts-one-email probing shapes.
 */
@Service
public class RateLimiter {

    private final RateLimitBucketStore bucketStore;
    private final AuthProperties properties;

    public RateLimiter(RateLimitBucketStore bucketStore, AuthProperties properties) {
        this.bucketStore = bucketStore;
        this.properties = properties;
    }

    public boolean allowLogin(String clientIp, String email) {
        return allow("login", properties.loginRateLimit(), clientIp, email);
    }

    public boolean allowMailAction(String clientIp, String email) {
        return allow("mail", properties.mailRateLimit(), clientIp, email);
    }

    private boolean allow(String scope, AuthProperties.RateLimit limit, String clientIp, String email) {
        // Both buckets are always recorded, even once one has already
        // tripped, so retrying after the IP bucket trips can't dodge the
        // email bucket's count for free.
        var ipAttempts = bucketStore.recordAttempt(scope + ":ip:" + clientIp, limit.window());
        var emailAttempts =
            bucketStore.recordAttempt(scope + ":email:" + Emails.normalise(email), limit.window());
        return ipAttempts <= limit.maxAttempts() && emailAttempts <= limit.maxAttempts();
    }
}
