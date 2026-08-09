package dev.yzlaboratory.alexandrea.catalog;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TMDB's base URL and image CDN prefix are stable, public API conventions
 * safe to default here. {@code apiKey} is account-specific and, per
 * ADR 0023, supplied at runtime from SSM Parameter Store (prod) or a local
 * environment variable (dev) — never defaulted or committed.
 */
@ConfigurationProperties(prefix = "alexandrea.catalog")
public record CatalogProperties(Tmdb tmdb) {

    public CatalogProperties {
        if (tmdb == null) {
            tmdb = new Tmdb(null, null, null);
        }
    }

    public record Tmdb(String baseUrl, String apiKey, String imageBaseUrl) {

        public Tmdb {
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://api.themoviedb.org/3";
            }
            if (imageBaseUrl == null || imageBaseUrl.isBlank()) {
                imageBaseUrl = "https://image.tmdb.org/t/p/w500";
            }
            if (apiKey == null) {
                apiKey = "";
            }
        }
    }
}
