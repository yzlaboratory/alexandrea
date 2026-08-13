package dev.yzlaboratory.alexandrea.catalog;

import java.util.Set;

/**
 * Which of this media type's filter fields, plus sort, {@link
 * CatalogService#searchCapabilities} finds locked right now because a text
 * search is active (ADR 0018's "Behavior under active text search") —
 * distinct from {@link CatalogService#availableFilters}'s "not offered for
 * this media type at all" signal. Both empty/false whenever no search is
 * active, or for Books, whose OpenLibrary search endpoint combines {@code
 * q=} with {@code sort=} and every field filter and so locks nothing.
 */
public record CatalogSearchCapabilities(Set<String> disabledFilters, boolean sortDisabled) {

    public static final CatalogSearchCapabilities NONE_DISABLED = new CatalogSearchCapabilities(Set.of(), false);
}
