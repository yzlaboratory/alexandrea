package dev.yzlaboratory.alexandrea.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Shared by every single-use link store (verification/reset tokens in
 * {@link TokenService}, pending email changes in {@link EmailChangeTokenStore}):
 * a 128-bit CSPRNG raw value, and its SHA-256 hash for storage. The raw value
 * is never persisted, so a database leak yields no usable links.
 */
final class SingleUseTokens {

    private static final int TOKEN_BYTES = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private SingleUseTokens() {}

    static String generate() {
        var bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    static String hash(String rawToken) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return URL_ENCODER.encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated on every JVM; its absence is unrecoverable.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
