package dev.yzlaboratory.alexandrea.catalog;

/**
 * One selectable value of a catalog filter — e.g. one TMDB or IGDB genre.
 * {@code value} is the provider's own native identifier (what a filter
 * request round-trips back to the provider, per ADR 0018's native-enum
 * filters); {@code label} is what the frontend renders.
 */
public record CatalogFilterOption(String value, String label) {}
