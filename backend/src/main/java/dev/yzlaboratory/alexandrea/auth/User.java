package dev.yzlaboratory.alexandrea.auth;

import java.time.Instant;

/**
 * An internal read model, never a boundary DTO: it carries the Argon2id
 * {@code passwordHash} and must not be serialised to a client.
 */
public record User(
    long id,
    String email,
    String passwordHash,
    boolean verified,
    String lastMediaType,
    Instant createdAt,
    Instant updatedAt
) {}
