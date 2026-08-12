package dev.yzlaboratory.alexandrea.catalog.web;

/**
 * {@code GET /api/catalog/{media_type}/preference}'s body — any field {@code
 * null} means the user has never set that piece for this media type, so the
 * frontend falls back to its own default (popularity/desc, no genre) rather
 * than treating this as an error.
 */
public record CatalogPreferenceResponse(String sortKey, String sortDirection, String genre) {}
