package dev.yzlaboratory.alexandrea.surface;

import java.time.Instant;

/**
 * One row of the shared preference store (ADR 0025), keyed
 * {@code (userId, surface, mediaType)}.
 */
public record SurfacePreference(
    long userId,
    String surface,
    String mediaType,
    String sortKey,
    String sortDirection,
    String filters,
    Instant updatedAt
) {}
