package dev.yzlaboratory.alexandrea.auth;

import java.time.Instant;

/**
 * An internal read model, never a boundary DTO: it carries the Argon2id
 * {@code passwordHash} and must not be serialised to a client. A null
 * {@code lastMediaType} means the User has never chosen one; reads default to
 * Movies (CONTEXT.md).
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
