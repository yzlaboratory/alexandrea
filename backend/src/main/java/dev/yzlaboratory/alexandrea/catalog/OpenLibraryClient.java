package dev.yzlaboratory.alexandrea.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Talks to OpenLibrary's {@code /trending/daily.json} and {@code
 * /search.json} endpoints (ADR 0018's "nothing applied" and "text search
 * active" rows) and maps their responses into the common {@link CatalogItem}
 * shape (ADR 0001). Needs no API key — OpenLibrary is a public,
 * unauthenticated API.
 */
@Component
public class OpenLibraryClient {

    // Package-private (not private): CatalogService references these
    // directly for its dispatch and cache-key building rather than
    // redeclaring its own copy that could silently drift out of sync.
    static final String PROVIDER = "OpenLibrary";
    static final String BOOKS_MEDIA_TYPE = "books";
    private static final double OPEN_LIBRARY_RATING_SCALE = 5.0;
    private static final int PAGE_SIZE = 20;
    private static final String WORK_KEY_PREFIX = "/works/";

    private final RestClient restClient;
    private final CatalogProperties properties;

    public OpenLibraryClient(RestClient openLibraryRestClient, CatalogProperties properties) {
        this.restClient = openLibraryRestClient;
        this.properties = properties;
    }

    public CatalogPageResult trendingBooks(int page) {
        var response = fetchTrending(page);
        var works = response.works() != null ? response.works() : List.<OpenLibraryWork>of();
        return toPageResult(works, page);
    }

    public CatalogPageResult search(String query, int page) {
        var response = fetchSearch(query, page);
        var docs = response.docs() != null ? response.docs() : List.<OpenLibraryWork>of();
        return toPageResult(docs, page);
    }

    // Shared by the trending feed and search: /search.json's "docs" entries
    // carry the same key/title/cover_i/first_publish_year fields as
    // /trending/daily.json's "works", so the same filter, mapping, and
    // hasMore heuristic apply to either response.
    private CatalogPageResult toPageResult(List<OpenLibraryWork> works, int page) {
        // A work with no "key" has no stable external id to cache it under;
        // CatalogCache.itemKey would otherwise fold every such work into the
        // same literal "null" segment, letting unrelated keyless works
        // silently overwrite each other's cache entry.
        var items = works.stream().filter(work -> work.key() != null).map(this::toItem).toList();
        // Neither endpoint reports a total-count field, so a full page is
        // the only available signal that more might follow; a next page
        // that turns out short or empty ends pagination correctly on its
        // own.
        var hasMore = items.size() >= PAGE_SIZE;
        return new CatalogPageResult(items, page, hasMore);
    }

    private OpenLibraryTrendingResponse fetchTrending(int page) {
        try {
            var response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/trending/daily.json")
                    .queryParam("limit", PAGE_SIZE)
                    .queryParam("offset", (page - 1) * PAGE_SIZE)
                    .build())
                .retrieve()
                .body(OpenLibraryTrendingResponse.class);
            return response != null ? response : OpenLibraryTrendingResponse.empty();
        } catch (RestClientException e) {
            throw new CatalogUpstreamException(PROVIDER, e);
        }
    }

    private OpenLibrarySearchResponse fetchSearch(String query, int page) {
        try {
            var response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/search.json")
                    .queryParam("q", query)
                    .queryParam("limit", PAGE_SIZE)
                    .queryParam("offset", (page - 1) * PAGE_SIZE)
                    .build())
                .retrieve()
                .body(OpenLibrarySearchResponse.class);
            return response != null ? response : OpenLibrarySearchResponse.empty();
        } catch (RestClientException e) {
            throw new CatalogUpstreamException(PROVIDER, e);
        }
    }

    private CatalogItem toItem(OpenLibraryWork work) {
        return new CatalogItem(
            PROVIDER,
            externalId(work.key()),
            BOOKS_MEDIA_TYPE,
            work.title(),
            coverUrl(work.coverId()),
            firstPublishDate(work.firstPublishYear()),
            // /trending/daily.json carries no rating field at all (verified
            // against the live endpoint — earlier code here read a
            // "ratings_average" field that doesn't exist in the real
            // response, so externalRating was silently always null rather
            // than null-when-absent per ADR 0006). The real community
            // rating lives behind a per-work /works/{id}/ratings.json call;
            // wiring that in is deferred to #48 rather than costing one
            // extra upstream round trip per book on every page load.
            null,
            OPEN_LIBRARY_RATING_SCALE
        );
    }

    private static String externalId(String key) {
        return key.startsWith(WORK_KEY_PREFIX) ? key.substring(WORK_KEY_PREFIX.length()) : key;
    }

    private String coverUrl(Long coverId) {
        if (coverId == null) {
            return null;
        }
        return properties.openLibrary().coverBaseUrl() + coverId + "-M.jpg";
    }

    // /trending/daily.json exposes only a first-publish *year*, never a full
    // date the way TMDB's release_date does. Jan 1 is the best available
    // precision; this field is display-only in this slice (sort/filter is
    // #40+), so the precision loss has no behavioural consequence.
    private static LocalDate firstPublishDate(Integer firstPublishYear) {
        return firstPublishYear != null ? LocalDate.of(firstPublishYear, 1, 1) : null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenLibraryTrendingResponse(List<OpenLibraryWork> works) {
        static OpenLibraryTrendingResponse empty() {
            return new OpenLibraryTrendingResponse(List.of());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenLibrarySearchResponse(List<OpenLibraryWork> docs) {
        static OpenLibrarySearchResponse empty() {
            return new OpenLibrarySearchResponse(List.of());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenLibraryWork(
        String key,
        String title,
        @JsonProperty("cover_i") Long coverId,
        @JsonProperty("first_publish_year") Integer firstPublishYear
    ) {}
}
