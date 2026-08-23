package dev.yzlaboratory.alexandrea.catalog;

/**
 * The four sort keys and two directions ADR 0018's capability map defines,
 * shared by {@link CatalogService} (which validates a request against these)
 * and each provider client's own field mapping (which switches on them) —
 * one definition rather than four independently-typed copies of the same
 * literal strings that could silently drift apart.
 */
final class CatalogSort {

    static final String POPULARITY = "popularity";
    static final String RELEASE_DATE = "release_date";
    static final String TITLE = "title";
    static final String EXTERNAL_RATING = "external_rating";

    static final String ASCENDING = "asc";
    static final String DESCENDING = "desc";

    private CatalogSort() {}
}
