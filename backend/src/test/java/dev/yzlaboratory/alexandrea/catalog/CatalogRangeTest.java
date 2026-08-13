package dev.yzlaboratory.alexandrea.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The "min,max" encoding runtime and page-count filters (ADR 0018) round-
 * trip through {@link CatalogFilterField}'s single opaque string value —
 * this class owns parsing and validating that encoding in isolation, since
 * both {@code TmdbClient} and {@code OpenLibraryClient} lean on it to build
 * their own provider-specific range query.
 */
class CatalogRangeTest {

    @Test
    void parsesAFullRangeWithBothBoundsPresent() {
        var range = CatalogRange.parse("90,180");

        assertThat(range).isPresent();
        assertThat(range.get().min()).isEqualTo(90);
        assertThat(range.get().max()).isEqualTo(180);
    }

    @Test
    void parsesAnOpenEndedMinOnlyRangeAsANullMax() {
        var range = CatalogRange.parse("90,");

        assertThat(range).isPresent();
        assertThat(range.get().min()).isEqualTo(90);
        assertThat(range.get().max()).isNull();
    }

    @Test
    void parsesAnOpenEndedMaxOnlyRangeAsANullMin() {
        var range = CatalogRange.parse(",180");

        assertThat(range).isPresent();
        assertThat(range.get().min()).isNull();
        assertThat(range.get().max()).isEqualTo(180);
    }

    @Test
    void aMinEqualToMaxIsValid() {
        assertThat(CatalogRange.parse("120,120")).isPresent();
    }

    @Test
    void aZeroMinIsValid() {
        assertThat(CatalogRange.parse("0,180")).isPresent();
    }

    @Test
    void bothBoundsBlankIsRejectedAsNoFilterAtAll() {
        assertThat(CatalogRange.parse(",")).isEmpty();
    }

    @Test
    void aNullEncodedValueIsRejected() {
        assertThat(CatalogRange.parse(null)).isEmpty();
    }

    @Test
    void aMinGreaterThanMaxIsRejected() {
        assertThat(CatalogRange.parse("180,90")).isEmpty();
    }

    @Test
    void aNegativeMinIsRejected() {
        assertThat(CatalogRange.parse("-10,90")).isEmpty();
    }

    @Test
    void aNegativeMaxIsRejected() {
        assertThat(CatalogRange.parse("10,-90")).isEmpty();
    }

    @Test
    void nonNumericBoundsAreRejectedRatherThanThrowing() {
        assertThat(CatalogRange.parse("abc,def")).isEmpty();
    }

    @Test
    void aNonNumericSingleBoundIsRejectedRatherThanThrowing() {
        assertThat(CatalogRange.parse("abc,180")).isEmpty();
    }

    @Test
    void missingTheCommaEntirelyIsRejected() {
        assertThat(CatalogRange.parse("90")).isEmpty();
    }

    @Test
    void tooManyCommasIsRejected() {
        assertThat(CatalogRange.parse("90,120,180")).isEmpty();
    }

    @Test
    void anEmptyStringIsRejected() {
        assertThat(CatalogRange.parse("")).isEmpty();
    }

    @Test
    void wholeNumberOnlyBoundsWithADecimalPointAreRejectedRatherThanTruncated() {
        assertThat(CatalogRange.parse("90.5,180")).isEmpty();
    }

    @Test
    void surroundingWhitespaceOnABoundIsTolerated() {
        var range = CatalogRange.parse(" 90 , 180 ");

        assertThat(range).isPresent();
        assertThat(range.get().min()).isEqualTo(90);
        assertThat(range.get().max()).isEqualTo(180);
    }

    @Test
    void isValidMirrorsParseForAWellFormedRange() {
        assertThat(CatalogRange.isValid("90,180")).isTrue();
    }

    @Test
    void isValidMirrorsParseForAMalformedRange() {
        assertThat(CatalogRange.isValid("not-a-range")).isFalse();
    }
}
