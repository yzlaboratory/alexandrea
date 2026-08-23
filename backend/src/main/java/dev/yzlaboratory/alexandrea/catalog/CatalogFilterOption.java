package dev.yzlaboratory.alexandrea.catalog;

/**
 * One selectable value of a catalog filter — e.g. one TMDB or IGDB genre, or
 * one of Books' curated genres (ADR 0013). {@code value} is what a filter
 * request round-trips back to {@code CatalogService}: the provider's own
 * native identifier for Movies/TV/Games' native-enum filters (ADR 0018), or
 * the curated genre's own app-owned key for Books. {@code label} is what
 * the frontend renders.
 */
public record CatalogFilterOption(String value, String label) {}
