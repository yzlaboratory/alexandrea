package dev.yzlaboratory.alexandrea.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * In {@code verificationUrlTemplate}/{@code resetUrlTemplate}, {@code {token}} is
 * substituted with the raw token so the API and SPA agree on one route per flow.
 */
@ConfigurationProperties(prefix = "alexandrea.auth")
public record AuthProperties(
    Duration verificationTokenTtl,
    String verificationUrlTemplate,
    Duration resetTokenTtl,
    String resetUrlTemplate,
    Duration emailChangeTokenTtl,
    String emailChangeUrlTemplate,
    RateLimit loginRateLimit,
    RateLimit mailRateLimit
) {

    public AuthProperties {
        if (verificationTokenTtl == null) {
            verificationTokenTtl = Duration.ofHours(24);
        }
        if (verificationUrlTemplate == null || verificationUrlTemplate.isBlank()) {
            verificationUrlTemplate = "http://localhost:5173/verify?token={token}";
        }
        if (resetTokenTtl == null) {
            resetTokenTtl = Duration.ofHours(1);
        }
        if (resetUrlTemplate == null || resetUrlTemplate.isBlank()) {
            resetUrlTemplate = "http://localhost:5173/reset-password?token={token}";
        }
        if (emailChangeTokenTtl == null) {
            emailChangeTokenTtl = Duration.ofHours(24);
        }
        if (emailChangeUrlTemplate == null || emailChangeUrlTemplate.isBlank()) {
            emailChangeUrlTemplate = "http://localhost:5173/confirm-email-change?token={token}";
        }
        // Defaults are the windows ADR 0024 names: ~10/15min for login, ~5/hour
        // for every mail-sending endpoint.
        if (loginRateLimit == null) {
            loginRateLimit = new RateLimit(10, Duration.ofMinutes(15));
        }
        if (mailRateLimit == null) {
            mailRateLimit = new RateLimit(5, Duration.ofHours(1));
        }
    }

    public record RateLimit(int maxAttempts, Duration window) {}
}
