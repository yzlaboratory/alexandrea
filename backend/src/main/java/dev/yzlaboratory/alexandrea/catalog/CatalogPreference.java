package dev.yzlaboratory.alexandrea.catalog;

/**
 * The signed-in user's persisted Catalog sort and genre filter for one media
 * type (ADR 0025) — any field is {@code null} when the user has never set
 * that piece (or, for {@code genre}, when the persisted value no longer
 * matches the current genre vocabulary and was dropped on read).
 */
public record CatalogPreference(String sortKey, String sortDirection, String genre) {}
