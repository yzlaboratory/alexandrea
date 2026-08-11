package dev.yzlaboratory.alexandrea.catalog.web;

/**
 * {@code GET /api/catalog/{media_type}/sort-preference}'s body — both
 * fields {@code null} means the user has never chosen a sort for this media
 * type, so the frontend falls back to its own popularity/desc default
 * rather than treating this as an error.
 */
public record SortPreferenceResponse(String sortKey, String sortDirection) {}
