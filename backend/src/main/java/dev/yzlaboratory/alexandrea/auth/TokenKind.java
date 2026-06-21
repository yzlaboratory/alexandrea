package dev.yzlaboratory.alexandrea.auth;

/**
 * The kinds of single-use, expiring token Alexandrea issues (ADR 0021).
 *
 * <p>Only {@link #VERIFICATION} exists today; password-reset and email-change
 * kinds are added as new constants when those flows land, and each gets its TTL
 * from config via {@code TokenService}. The {@code storageValue} is the literal
 * written to {@code auth_tokens.kind} and must stay stable once persisted rows
 * depend on it.
 */
public enum TokenKind {

    VERIFICATION("verification");

    private final String storageValue;

    TokenKind(String storageValue) {
        this.storageValue = storageValue;
    }

    public String storageValue() {
        return storageValue;
    }
}
