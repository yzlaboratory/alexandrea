package dev.yzlaboratory.alexandrea.auth;

/**
 * The {@code storageValue} is the literal written to {@code auth_tokens.kind} and
 * must stay stable once persisted rows depend on it.
 */
public enum TokenKind {

    VERIFICATION("verification"),
    RESET("reset");

    private final String storageValue;

    TokenKind(String storageValue) {
        this.storageValue = storageValue;
    }

    public String storageValue() {
        return storageValue;
    }
}
