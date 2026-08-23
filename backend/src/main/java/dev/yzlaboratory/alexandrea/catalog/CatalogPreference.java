package dev.yzlaboratory.alexandrea.catalog;

import java.util.Map;

/**
 * The signed-in user's persisted Catalog sort and filter selections for one
 * media type (ADR 0025) — {@code sortKey}/{@code sortDirection} are {@code
 * null} when the user has never set either. {@code filters} is keyed by
 * filter field (see {@link CatalogFilterKeys}) to that field's single
 * selected value; a field simply absent from the map means "never set" or
 * "no longer valid for the current vocabulary" — never a key present with a
 * {@code null} value. Empty rather than {@code null} when nothing is set, so
 * callers never have to null-check before reading it.
 */
public record CatalogPreference(String sortKey, String sortDirection, Map<String, String> filters) {}
