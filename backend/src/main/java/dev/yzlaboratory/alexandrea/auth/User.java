package dev.yzlaboratory.alexandrea.auth;

import java.time.Instant;
import java.util.Optional;

/**
 * A row of the {@code users} identity store (ADR 0021).
 *
 * <p>This is an internal read model, never a boundary DTO — it carries the
 * Argon2id {@code passwordHash} and so must not be serialised to a client.
 * {@code lastMediaType} is the Sticky media type (CONTEXT.md); empty means the
 * User has never chosen one and reads default to Movies.
 */
public record User(
    long id,
    String email,
    String passwordHash,
    boolean verified,
    Optional<String> lastMediaType,
    Instant createdAt,
    Instant updatedAt
) {}
