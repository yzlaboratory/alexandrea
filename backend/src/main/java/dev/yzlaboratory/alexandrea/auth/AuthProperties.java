package dev.yzlaboratory.alexandrea.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * In {@code verificationUrlTemplate}, {@code {token}} is substituted with the raw
 * token so the API and SPA agree on one verification route.
 */
@ConfigurationProperties(prefix = "alexandrea.auth")
public record AuthProperties(
    Duration verificationTokenTtl,
    String verificationUrlTemplate
) {

    public AuthProperties {
        if (verificationTokenTtl == null) {
            verificationTokenTtl = Duration.ofHours(24);
        }
        if (verificationUrlTemplate == null || verificationUrlTemplate.isBlank()) {
            verificationUrlTemplate = "http://localhost:5173/verify?token={token}";
        }
    }
}
