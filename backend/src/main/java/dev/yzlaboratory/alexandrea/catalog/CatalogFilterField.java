package dev.yzlaboratory.alexandrea.catalog;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One independently-selectable filter kind (ADR 0018) — genre, original
 * language, or available-in language today. {@link CatalogService} holds one
 * of these per kind and treats every kind identically when validating,
 * merging, and persisting a browse request's filters, rather than hand-
 * rolling a special case per kind: a future filter kind is one new entry in
 * that list, not a new code path.
 */
record CatalogFilterField(String key, Predicate<String> supports, Function<String, List<CatalogFilterOption>> optionsFor) {

    boolean isValidValue(String mediaType, String value) {
        if (value == null || value.isBlank() || !supports.test(mediaType)) {
            return false;
        }
        try {
            return optionsFor.apply(mediaType).stream().anyMatch(option -> option.value().equals(value));
        } catch (CatalogUpstreamException e) {
            return false;
        }
    }
}
