package dev.yzlaboratory.alexandrea.auth;

import java.time.Duration;

/**
 * The kinds of single-use, expiring token Alexandrea issues (ADR 0021).
 *
 * <p>Only {@link #VERIFICATION} is wired for the signup tracer bullet (#19);
 * {@code PASSWORD_RESET} (#23) and {@code EMAIL_CHANGE} (#25) are deliberately
 * absent until those slices land. Each kind carries its own time-to-live so the
 * {@link TokenService} stays kind-agnostic. The {@code storageValue} is the
 * literal written to {@code auth_tokens.kind} and must stay stable once a
 * migration depends on it.
 */
public enum TokenKind {

    VERIFICATION("verification", Duration.ofHours(24));

    private final String storageValue;
    private final Duration timeToLive;

    TokenKind(String storageValue, Duration timeToLive) {
        this.storageValue = storageValue;
        this.timeToLive = timeToLive;
    }

    public String storageValue() {
        return storageValue;
    }

    public Duration timeToLive() {
        return timeToLive;
    }
}
