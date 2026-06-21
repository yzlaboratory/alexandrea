package dev.yzlaboratory.alexandrea.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised auth knobs (ADR 0021 keeps token lifetimes config-driven, not
 * pinned in code).
 *
 * @param verificationTokenTtl how long an email-verification link stays valid;
 *     defaults to 24h and is overridable per environment.
 * @param verificationUrlTemplate the SPA URL the verification link points at;
 *     {@code {token}} is substituted with the raw token. The mail layer builds
 *     the clickable link from this so the API and SPA agree on one route.
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
