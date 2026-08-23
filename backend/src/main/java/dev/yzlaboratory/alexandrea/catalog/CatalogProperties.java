package dev.yzlaboratory.alexandrea.catalog;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Each provider's base URL (and, where relevant, image CDN prefix) is a
 * stable, public API convention safe to default here. Account-specific
 * credentials (TMDB's {@code apiKey}, IGDB's Twitch {@code clientId}/
 * {@code clientSecret}) are, per ADR 0023, supplied at runtime from SSM
 * Parameter Store (prod) or a local environment variable (dev) — never
 * defaulted or committed. OpenLibrary needs no credential: it's a public,
 * unauthenticated API.
 */
@ConfigurationProperties(prefix = "alexandrea.catalog")
public record CatalogProperties(Tmdb tmdb, OpenLibrary openLibrary, Igdb igdb) {

    public CatalogProperties {
        if (tmdb == null) {
            tmdb = new Tmdb(null, null, null);
        }
        if (openLibrary == null) {
            openLibrary = new OpenLibrary(null, null);
        }
        if (igdb == null) {
            igdb = new Igdb(null, null, null, null, null);
        }
    }

    // Shared by Tmdb's and Igdb's own toString() overrides below — a
    // credential must never appear in cleartext in a log line or a test
    // assertion failure that happens to print this properties object.
    private static String redact(String secret) {
        return secret == null || secret.isEmpty() ? "(empty)" : "(redacted)";
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

        @Override
        public String toString() {
            return "Tmdb[baseUrl=%s, apiKey=%s, imageBaseUrl=%s]".formatted(baseUrl, redact(apiKey), imageBaseUrl);
        }
    }

    public record OpenLibrary(String baseUrl, String coverBaseUrl) {

        public OpenLibrary {
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://openlibrary.org";
            }
            if (coverBaseUrl == null || coverBaseUrl.isBlank()) {
                coverBaseUrl = "https://covers.openlibrary.org/b/id/";
            }
        }
    }

    public record Igdb(String baseUrl, String twitchBaseUrl, String coverBaseUrl, String clientId, String clientSecret) {

        public Igdb {
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://api.igdb.com/v4";
            }
            if (twitchBaseUrl == null || twitchBaseUrl.isBlank()) {
                twitchBaseUrl = "https://id.twitch.tv";
            }
            if (coverBaseUrl == null || coverBaseUrl.isBlank()) {
                coverBaseUrl = "https://images.igdb.com/igdb/image/upload/t_cover_big/";
            }
            if (clientId == null) {
                clientId = "";
            }
            if (clientSecret == null) {
                clientSecret = "";
            }
        }

        // clientId is not redacted: Twitch's client-credentials flow treats
        // it as a public identifier, not a secret — only clientSecret is.
        @Override
        public String toString() {
            return "Igdb[baseUrl=%s, twitchBaseUrl=%s, coverBaseUrl=%s, clientId=%s, clientSecret=%s]"
                .formatted(baseUrl, twitchBaseUrl, coverBaseUrl, clientId, redact(clientSecret));
        }
    }
}
