package dev.yzlaboratory.alexandrea.auth;

import java.io.Serializable;

/**
 * The security principal stored in the session — serialized into
 * {@code SPRING_SESSION_ATTRIBUTES} on every request, so it stays minimal
 * rather than carrying the full {@link User}.
 */
public record AuthenticatedUser(long userId, String email) implements Serializable {}
