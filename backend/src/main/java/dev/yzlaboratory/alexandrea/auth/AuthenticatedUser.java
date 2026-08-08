package dev.yzlaboratory.alexandrea.auth;

import java.io.Serializable;
import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * The security principal stored in the session — serialized into
 * {@code SPRING_SESSION_ATTRIBUTES} on every request, so it stays minimal
 * rather than carrying the full {@link User}.
 *
 * <p>Implements {@link AuthenticatedPrincipal} so Spring Session indexes
 * sessions by a stable user id, not this record's default {@code toString()}.
 */
public record AuthenticatedUser(long userId, String email) implements Serializable, AuthenticatedPrincipal {

    @Override
    public String getName() {
        return principalName(userId);
    }

    public static String principalName(long userId) {
        return String.valueOf(userId);
    }
}
