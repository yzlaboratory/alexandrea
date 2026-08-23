package dev.yzlaboratory.alexandrea.catalog;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One independently-selectable filter kind (ADR 0018) — genre, original
 * language, available-in language, runtime, or page count today.
 * {@link CatalogService} holds one of these per kind and treats every kind
 * identically when validating, merging, and persisting a browse request's
 * filters, rather than hand-rolling a special case per kind: a future
 * filter kind is one new entry in that list, not a new code path.
 *
 * <p>{@code valueValidator} is what actually differs between the two filter
 * families this mechanism covers. Genre/original-language/available-in-
 * language each pick one value out of {@code optionsFor}'s enumerated list —
 * the 3-arg constructor below derives that check automatically, so those
 * three fields' call sites in {@code CatalogService} need not repeat it.
 * Runtime and page count have no enumerable option list at all (a range has
 * infinitely many valid values) — they're built with the 4-arg canonical
 * constructor directly, passing a validator that parses and range-checks an
 * encoded "min,max" string ({@link CatalogRange}) instead of matching
 * against {@code optionsFor}.
 */
record CatalogFilterField(
    String key, Predicate<String> supports, Function<String, List<CatalogFilterOption>> optionsFor,
    BiPredicate<String, String> valueValidator
) {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogFilterField.class);

    /**
     * Convenience constructor for the three option-enumerated fields (genre,
     * original language, available-in language): a requested value is valid
     * exactly when it matches one of {@code optionsFor}'s own values for
     * this media type.
     */
    CatalogFilterField(String key, Predicate<String> supports, Function<String, List<CatalogFilterOption>> optionsFor) {
        this(
            key, supports, optionsFor,
            (mediaType, value) -> optionsFor.apply(mediaType).stream().anyMatch(option -> option.value().equals(value))
        );
    }

    boolean isValidValue(String mediaType, String value) {
        if (value == null || value.isBlank() || !supports.test(mediaType)) {
            return false;
        }
        try {
            return valueValidator.test(mediaType, value);
        } catch (CatalogUpstreamException e) {
            LOG.warn(
                "Could not verify {} value {} for {} against a temporarily-unavailable vocabulary; dropping it", key, value, mediaType, e
            );
            return false;
        }
    }
}
