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
    private static final String EXTERNAL_RATING_SORT_KEY = "external_rating";
    // Explicitly requested only on the sorted/discover path: /trending/daily.json
    // and /search.json's plain q= responses never carry ratings_average (see
    // toItem's rationale below), so the field must be asked for by name here to
    // get it back at all.
    private static final String SORTED_FIELDS = "key,title,cover_i,first_publish_year,ratings_average";

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

    // ADR 0018's "filter/sort applied" row: /search.json replaces
    // /trending/daily.json once a sort is chosen, with q=* matching every
    // work (the same Solr "match everything" idiom as an empty query) since
    // no text search is active.
    public CatalogPageResult sortedBooks(String sortKey, String direction, int page) {
        var response = fetchSorted(sortKey, direction, page);
        var docs = response.docs() != null ? response.docs() : List.<OpenLibraryWork>of();
        var keyedDocs = docs.stream().filter(work -> work.key() != null).toList();
        // hasMore reflects the upstream page's own fullness, computed before
        // the external_rating null-filter below — a page that upstream filled
        // but whose books happen to be mostly unrated must not look like the
        // end of the feed (ADR 0006's shrink is a display concern, not a
        // pagination one).
        var hasMore = keyedDocs.size() >= PAGE_SIZE;
        var items = keyedDocs.stream().map(this::toSortedItem).toList();
        if (EXTERNAL_RATING_SORT_KEY.equals(sortKey)) {
            items = items.stream().filter(item -> item.externalRating() != null).toList();
        }
        return new CatalogPageResult(items, page, hasMore);
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

    private OpenLibrarySearchResponse fetchSorted(String sortKey, String direction, int page) {
        try {
            var response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/search.json")
                    .queryParam("q", "*")
                    .queryParam("sort", sortParam(sortKey, direction))
                    .queryParam("fields", SORTED_FIELDS)
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

    // ADR 0018 pins only the literal sort= value for each sort's default
    // direction ("trending", "new", "title", "rating"). The opposite
    // direction is requested by appending the explicit direction keyword to
    // that same preset — the parenthetical raw-field detail the ADR gives
    // for title ("title_sort asc") and rating ("ratings_sortable desc")
    // confirms each preset is itself a field+direction pair under the hood,
    // so suffixing the reverse keyword flips it the same way TMDB's and
    // IGDB's own sort params do.
    private static String sortParam(String sortKey, String direction) {
        var preset = switch (sortKey) {
            case "popularity" -> "trending";
            case "release_date" -> "new";
            case "title" -> "title";
            case EXTERNAL_RATING_SORT_KEY -> "rating";
            default -> throw new IllegalArgumentException("Unsupported sort key: " + sortKey);
        };
        var defaultDirection = "title".equals(sortKey) ? "asc" : "desc";
        return direction.equals(defaultDirection) ? preset : preset + " " + direction;
    }

    private CatalogItem toItem(OpenLibraryWork work) {
        // /trending/daily.json and /search.json's plain (non-sorted)
        // responses carry no rating field at all (verified against the live
        // endpoint — earlier code here read a "ratings_average" field that
        // doesn't exist in either response, so externalRating was silently
        // always null rather than null-when-absent per ADR 0006). The real
        // community rating lives behind a per-work /works/{id}/ratings.json
        // call; wiring that into these two feeds is deferred to #48 rather
        // than costing one extra upstream round trip per book on every page
        // load. sortedBooks's own mapping (toSortedItem) reads the field
        // directly instead, since sortParam's "external_rating" path
        // explicitly requests it via SORTED_FIELDS.
        return toItem(work, null);
    }

    private CatalogItem toSortedItem(OpenLibraryWork work) {
        return toItem(work, work.ratingsAverage());
    }

    private CatalogItem toItem(OpenLibraryWork work, Double externalRating) {
        return new CatalogItem(
            PROVIDER,
            externalId(work.key()),
            BOOKS_MEDIA_TYPE,
            work.title(),
            coverUrl(work.coverId()),
            firstPublishDate(work.firstPublishYear()),
            externalRating,
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
        @JsonProperty("first_publish_year") Integer firstPublishYear,
        @JsonProperty("ratings_average") Double ratingsAverage
    ) {}
}
