package dev.yzlaboratory.alexandrea.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CatalogPropertiesTest {

    @Test
    void unsetTmdbFieldsDefaultToThePublicTmdbConventions() {
        var properties = new CatalogProperties(new CatalogProperties.Tmdb(null, null, null), null, null);

        assertThat(properties.tmdb().baseUrl()).isEqualTo("https://api.themoviedb.org/3");
        assertThat(properties.tmdb().imageBaseUrl()).isEqualTo("https://image.tmdb.org/t/p/w500");
        // Unlike the URLs, the key has no safe default (ADR 0023) — it's left
        // blank rather than null so callers can build a query string without
        // a null check.
        assertThat(properties.tmdb().apiKey()).isEmpty();
    }

    @Test
    void blankTmdbFieldsAlsoFallBackToTheDefaults() {
        var properties =
            new CatalogProperties(new CatalogProperties.Tmdb("  ", "real-key", " "), null, null);

        assertThat(properties.tmdb().baseUrl()).isEqualTo("https://api.themoviedb.org/3");
        assertThat(properties.tmdb().imageBaseUrl()).isEqualTo("https://image.tmdb.org/t/p/w500");
        assertThat(properties.tmdb().apiKey()).isEqualTo("real-key");
    }

    @Test
    void explicitlyConfiguredValuesPassThroughUnchanged() {
        var properties = new CatalogProperties(
            new CatalogProperties.Tmdb("https://example.test/tmdb", "a-real-key", "https://example.test/img"),
            null,
            null);

        assertThat(properties.tmdb().baseUrl()).isEqualTo("https://example.test/tmdb");
        assertThat(properties.tmdb().apiKey()).isEqualTo("a-real-key");
        assertThat(properties.tmdb().imageBaseUrl()).isEqualTo("https://example.test/img");
    }

    @Test
    void aWhollyUnsetCatalogPropertiesStillProducesUsableDefaults() {
        var properties = new CatalogProperties(null, null, null);

        assertThat(properties.tmdb().baseUrl()).isEqualTo("https://api.themoviedb.org/3");
        assertThat(properties.openLibrary().baseUrl()).isEqualTo("https://openlibrary.org");
        assertThat(properties.igdb().baseUrl()).isEqualTo("https://api.igdb.com/v4");
    }

    @Test
    void unsetOpenLibraryFieldsDefaultToThePublicOpenLibraryConventions() {
        var properties = new CatalogProperties(null, new CatalogProperties.OpenLibrary(null, null), null);

        assertThat(properties.openLibrary().baseUrl()).isEqualTo("https://openlibrary.org");
        assertThat(properties.openLibrary().coverBaseUrl()).isEqualTo("https://covers.openlibrary.org/b/id/");
    }

    @Test
    void blankOpenLibraryFieldsAlsoFallBackToTheDefaults() {
        var properties = new CatalogProperties(null, new CatalogProperties.OpenLibrary(" ", " "), null);

        assertThat(properties.openLibrary().baseUrl()).isEqualTo("https://openlibrary.org");
        assertThat(properties.openLibrary().coverBaseUrl()).isEqualTo("https://covers.openlibrary.org/b/id/");
    }

    @Test
    void explicitlyConfiguredOpenLibraryValuesPassThroughUnchanged() {
        var properties = new CatalogProperties(
            null, new CatalogProperties.OpenLibrary("https://example.test/ol", "https://example.test/covers/"), null);

        assertThat(properties.openLibrary().baseUrl()).isEqualTo("https://example.test/ol");
        assertThat(properties.openLibrary().coverBaseUrl()).isEqualTo("https://example.test/covers/");
    }

    @Test
    void unsetIgdbFieldsDefaultToThePublicIgdbAndTwitchConventions() {
        var properties = new CatalogProperties(null, null, new CatalogProperties.Igdb(null, null, null, null, null));

        assertThat(properties.igdb().baseUrl()).isEqualTo("https://api.igdb.com/v4");
        assertThat(properties.igdb().twitchBaseUrl()).isEqualTo("https://id.twitch.tv");
        assertThat(properties.igdb().coverBaseUrl())
            .isEqualTo("https://images.igdb.com/igdb/image/upload/t_cover_big/");
        // Unlike the URLs, credentials have no safe default (ADR 0023) — left
        // blank rather than null so callers can build a request without a
        // null check.
        assertThat(properties.igdb().clientId()).isEmpty();
        assertThat(properties.igdb().clientSecret()).isEmpty();
    }

    @Test
    void blankIgdbUrlFieldsAlsoFallBackToTheDefaults() {
        var properties =
            new CatalogProperties(null, null, new CatalogProperties.Igdb(" ", " ", " ", "a-client-id", "a-secret"));

        assertThat(properties.igdb().baseUrl()).isEqualTo("https://api.igdb.com/v4");
        assertThat(properties.igdb().twitchBaseUrl()).isEqualTo("https://id.twitch.tv");
        assertThat(properties.igdb().coverBaseUrl())
            .isEqualTo("https://images.igdb.com/igdb/image/upload/t_cover_big/");
        assertThat(properties.igdb().clientId()).isEqualTo("a-client-id");
        assertThat(properties.igdb().clientSecret()).isEqualTo("a-secret");
    }

    @Test
    void explicitlyConfiguredIgdbValuesPassThroughUnchanged() {
        var properties = new CatalogProperties(
            null,
            null,
            new CatalogProperties.Igdb(
                "https://example.test/igdb",
                "https://example.test/twitch",
                "https://example.test/covers/",
                "client-id",
                "client-secret"));

        assertThat(properties.igdb().baseUrl()).isEqualTo("https://example.test/igdb");
        assertThat(properties.igdb().twitchBaseUrl()).isEqualTo("https://example.test/twitch");
        assertThat(properties.igdb().coverBaseUrl()).isEqualTo("https://example.test/covers/");
        assertThat(properties.igdb().clientId()).isEqualTo("client-id");
        assertThat(properties.igdb().clientSecret()).isEqualTo("client-secret");
    }

    @Test
    void tmdbToStringRedactsTheApiKey() {
        var tmdb = new CatalogProperties.Tmdb("https://example.test/tmdb", "a-real-secret-key", "https://example.test/img");

        assertThat(tmdb.toString())
            .contains("https://example.test/tmdb", "https://example.test/img")
            .doesNotContain("a-real-secret-key");
    }

    @Test
    void igdbToStringRedactsOnlyTheClientSecretNotTheClientId() {
        var igdb = new CatalogProperties.Igdb(
            "https://example.test/igdb",
            "https://example.test/twitch",
            "https://example.test/covers/",
            "a-client-id",
            "a-real-secret");

        var rendered = igdb.toString();

        assertThat(rendered)
            .contains("https://example.test/igdb", "https://example.test/twitch", "a-client-id")
            .doesNotContain("a-real-secret");
    }
}
