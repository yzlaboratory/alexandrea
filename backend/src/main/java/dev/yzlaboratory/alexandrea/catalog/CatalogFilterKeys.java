package dev.yzlaboratory.alexandrea.catalog;

/**
 * The filter field keys ADR 0018 defines — shared between {@link
 * CatalogService}'s per-field validation and {@code CatalogController}'s
 * request parsing so the two never drift apart on what a field is called.
 */
public final class CatalogFilterKeys {

    public static final String GENRE = "genre";
    public static final String ORIGINAL_LANGUAGE = "originalLanguage";
    public static final String AVAILABLE_IN_LANGUAGE = "availableInLanguage";
    public static final String RUNTIME = "runtime";
    public static final String PAGE_COUNT = "pageCount";

    private CatalogFilterKeys() {}
}
