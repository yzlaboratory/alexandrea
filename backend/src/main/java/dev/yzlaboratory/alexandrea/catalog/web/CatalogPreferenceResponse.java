package dev.yzlaboratory.alexandrea.catalog.web;

import java.util.Map;

/**
 * {@code GET /api/catalog/{media_type}/preference}'s body — {@code
 * sortKey}/{@code sortDirection} {@code null} means the user has never set
 * either, so the frontend falls back to its own default (popularity/desc).
 * {@code filters} carries only the fields currently set, keyed by one of
 * {@link dev.yzlaboratory.alexandrea.catalog.CatalogFilterKeys}'s constants;
 * an absent field means no value is selected for it.
 */
public record CatalogPreferenceResponse(String sortKey, String sortDirection, Map<String, String> filters) {}
