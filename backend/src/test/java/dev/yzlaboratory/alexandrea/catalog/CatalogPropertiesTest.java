package dev.yzlaboratory.alexandrea.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CatalogPropertiesTest {

    @Test
    void unsetTmdbFieldsDefaultToThePublicTmdbConventions() {
        var properties = new CatalogProperties(new CatalogProperties.Tmdb(null, null, null));

        assertThat(properties.tmdb().baseUrl()).isEqualTo("https://api.themoviedb.org/3");
        assertThat(properties.tmdb().imageBaseUrl()).isEqualTo("https://image.tmdb.org/t/p/w500");
        // Unlike the URLs, the key has no safe default (ADR 0023) — it's left
        // blank rather than null so callers can build a query string without
        // a null check.
        assertThat(properties.tmdb().apiKey()).isEmpty();
    }

    @Test
    void blankTmdbFieldsAlsoFallBackToTheDefaults() {
        var properties = new CatalogProperties(new CatalogProperties.Tmdb("  ", "real-key", " "));

        assertThat(properties.tmdb().baseUrl()).isEqualTo("https://api.themoviedb.org/3");
        assertThat(properties.tmdb().imageBaseUrl()).isEqualTo("https://image.tmdb.org/t/p/w500");
        assertThat(properties.tmdb().apiKey()).isEqualTo("real-key");
    }

    @Test
    void explicitlyConfiguredValuesPassThroughUnchanged() {
        var properties = new CatalogProperties(
            new CatalogProperties.Tmdb("https://example.test/tmdb", "a-real-key", "https://example.test/img"));

        assertThat(properties.tmdb().baseUrl()).isEqualTo("https://example.test/tmdb");
        assertThat(properties.tmdb().apiKey()).isEqualTo("a-real-key");
        assertThat(properties.tmdb().imageBaseUrl()).isEqualTo("https://example.test/img");
    }

    @Test
    void aWhollyUnsetCatalogPropertiesStillProducesUsableDefaults() {
        var properties = new CatalogProperties(null);

        assertThat(properties.tmdb().baseUrl()).isEqualTo("https://api.themoviedb.org/3");
    }
}
