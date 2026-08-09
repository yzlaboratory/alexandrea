package dev.yzlaboratory.alexandrea.catalog;

import java.time.LocalDate;

/**
 * The common shape every provider client maps its catalog data into
 * (ADR 0001): title, cover, release date, and external rating in the
 * provider's own scale. Identified by {@code (provider, externalId,
 * mediaType)} — the same triple CONTEXT.md's Catalog Item is keyed by, and
 * the same name: CONTEXT.md retires "entry" as an ambiguous synonym for this
 * concept, so this type is named after the domain term, not the retired one.
 *
 * <p>{@code externalRating} is nullable because not every provider rates
 * every item (see ADR 0006 for Books); TMDB always returns a numeric
 * {@code vote_average} for movies, defaulting to {@code 0.0} when unrated
 * rather than omitting the field.
 */
public record CatalogItem(
    String provider,
    String externalId,
    String mediaType,
    String title,
    String coverUrl,
    LocalDate releaseDate,
    Double externalRating,
    double externalRatingScale
) {}
