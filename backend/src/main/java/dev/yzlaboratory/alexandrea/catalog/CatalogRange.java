package dev.yzlaboratory.alexandrea.catalog;

import java.util.Optional;

/**
 * The {@code "<min>,<max>"} encoding the runtime and page-count filters
 * (ADR 0018) use for {@link CatalogFilterField}'s single opaque string
 * value (see {@link CatalogFilterKeys#RUNTIME}, {@link
 * CatalogFilterKeys#PAGE_COUNT}) — genre and the two language filters are
 * each already a single string value, so a range reuses that same shape
 * rather than widening the persisted {@code filters} map to a new value
 * type. Either bound may be blank for an open-ended range (e.g. {@code
 * "90,"} means "90 minutes or longer", {@code ",180"} means "180 minutes or
 * shorter") — but not both, since that carries no filtering intent at all
 * and a deselected control already represents "no filter" by omitting the
 * field entirely rather than by this encoding. A present min must not
 * exceed a present max.
 */
record CatalogRange(Integer min, Integer max) {

    static boolean isValid(String encoded) {
        return parse(encoded).isPresent();
    }

    /**
     * Empty when {@code encoded} isn't exactly two comma-separated optional
     * non-negative integers with min &lt;= max — malformed input (extra
     * commas, non-numeric bounds, a negative bound, min &gt; max, or both
     * bounds blank) is rejected by returning empty rather than throwing, the
     * same "drop it, don't crash" contract every other filter kind's {@link
     * CatalogFilterField#isValidValue} already follows.
     */
    static Optional<CatalogRange> parse(String encoded) {
        if (encoded == null) {
            return Optional.empty();
        }
        var parts = encoded.split(",", -1);
        if (parts.length != 2) {
            return Optional.empty();
        }
        Integer min;
        Integer max;
        try {
            min = parseBound(parts[0]);
            max = parseBound(parts[1]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (min == null && max == null) {
            return Optional.empty();
        }
        if ((min != null && min < 0) || (max != null && max < 0)) {
            return Optional.empty();
        }
        if (min != null && max != null && min > max) {
            return Optional.empty();
        }
        return Optional.of(new CatalogRange(min, max));
    }

    private static Integer parseBound(String raw) {
        var trimmed = raw.trim();
        return trimmed.isEmpty() ? null : Integer.valueOf(trimmed);
    }
}
