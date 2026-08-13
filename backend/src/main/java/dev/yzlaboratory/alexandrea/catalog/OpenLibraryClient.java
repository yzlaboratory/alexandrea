package dev.yzlaboratory.alexandrea.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Talks to OpenLibrary's {@code /trending/daily.json} and {@code
 * /search.json} endpoints — the latter for both title search and, with an
 * explicit {@code sort=}, the sorted/discover feed (ADR 0018's "nothing
 * applied", "filter/sort applied", and "text search active" rows) — and
 * maps their responses into the common {@link CatalogItem} shape (ADR 0001).
 * Needs no API key — OpenLibrary is a public, unauthenticated API.
 *
 * <p>{@code /search.json} returns intermittent HTTP 500s on some query
 * shapes (ADR 0018); {@link #search} and {@link #sortedBooks} both retry
 * such a failure with backoff via {@link #fetchWithRetry} before giving up,
 * rather than treating one 500 as "unsupported" or "empty".
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
    // Explicitly requested only on the sorted/discover path: /trending/daily.json
    // and /search.json's plain q= responses never carry ratings_average (see
    // toItem's rationale below), so the field must be asked for by name here to
    // get it back at all.
    private static final String SORTED_FIELDS = "key,title,cover_i,first_publish_year,ratings_average";
    private static final String MATCH_ALL_QUERY = "*";
    private static final int MAX_ATTEMPTS = 3;
    private static final List<Duration> RETRY_BACKOFF = List.of(Duration.ofMillis(50), Duration.ofMillis(100));

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
    // work (the same Solr "match everything" idiom as an empty query) when
    // none of a curated Books genre (ADR 0013), an available-in-language
    // filter, or a page-count range is applied — genreSubjectAliases empty
    // means no genre filter; availableInLanguageMarc3 null means no
    // language filter; pageCountRange null/unparseable means no page-count
    // filter; any combination of the three replaces q=* with their clauses,
    // ANDed together. OpenLibrary's language field on a work reflects every
    // edition's language, not the work's original language (a concept it
    // has no notion of at all), so filtering by it already matches ADR
    // 0018's "has an edition in this language" semantics without any extra
    // original-language exclusion to apply.
    public CatalogPageResult sortedBooks(
        String sortKey, String direction, List<String> genreSubjectAliases, String availableInLanguageMarc3, String pageCountRange, int page
    ) {
        var response = fetchSorted(sortKey, direction, genreSubjectAliases, availableInLanguageMarc3, pageCountRange, page);
        var docs = response.docs() != null ? response.docs() : List.<OpenLibraryWork>of();
        var keyedDocs = docs.stream().filter(work -> work.key() != null).toList();
        // hasMore reflects the upstream page's own fullness, computed before
        // the external_rating null-filter below — a page that upstream filled
        // but whose books happen to be mostly unrated must not look like the
        // end of the feed (ADR 0006's shrink is a display concern, not a
        // pagination one).
        var hasMore = keyedDocs.size() >= PAGE_SIZE;
        var items = keyedDocs.stream().map(this::toSortedItem).toList();
        if (CatalogSort.EXTERNAL_RATING.equals(sortKey)) {
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

    // Not wrapped in fetchWithRetry: ADR 0018's flaky-500 finding is specific
    // to /search.json's sort=/range query shapes, which this endpoint never
    // sends.
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
        return fetchWithRetry(() -> {
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
        });
    }

    private OpenLibrarySearchResponse fetchSorted(
        String sortKey, String direction, List<String> genreSubjectAliases, String availableInLanguageMarc3, String pageCountRange, int page
    ) {
        var query = combinedQuery(genreSubjectAliases, availableInLanguageMarc3, pageCountRange);
        return fetchWithRetry(() -> {
            var response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/search.json")
                    .queryParam("q", query)
                    .queryParam("sort", sortParam(sortKey, direction))
                    .queryParam("fields", SORTED_FIELDS)
                    .queryParam("limit", PAGE_SIZE)
                    .queryParam("offset", (page - 1) * PAGE_SIZE)
                    .build())
                .retrieve()
                .body(OpenLibrarySearchResponse.class);
            return response != null ? response : OpenLibrarySearchResponse.empty();
        });
    }

    // ADR 0018: /search.json returns intermittent HTTP 500s on some query
    // shapes, so one 500 must not be read as "unsupported" or "empty" — retry
    // a 5xx with a short backoff before giving up. A non-5xx failure (a
    // genuine 4xx, or a connection-level error) skips the backoff and fails
    // immediately, since a second attempt won't fix it.
    private <T> T fetchWithRetry(Supplier<T> request) {
        for (var attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return request.get();
            } catch (RestClientException e) {
                if (!(e instanceof HttpServerErrorException) || attempt == MAX_ATTEMPTS) {
                    throw new CatalogUpstreamException(PROVIDER, e);
                }
                sleep(RETRY_BACKOFF.get(attempt - 1));
            }
        }
        throw new IllegalStateException("fetchWithRetry's loop always returns or throws before exiting");
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CatalogUpstreamException(PROVIDER, e);
        }
    }

    // Package-private (not private): OpenLibraryClientTest asserts this
    // Solr fragment directly rather than only indirectly through a mocked
    // HTTP request's query string.
    //
    // Each alias is Solr phrase-quoted so a multi-word alias like "Science
    // fiction" matches as one phrase rather than splitting into two ORed
    // terms; backslashes and embedded quotes are escaped defensively even
    // though the alias list is our own maintained resource (ADR 0013).
    static String subjectQuery(List<String> subjectAliases) {
        return subjectAliases.stream()
            .map(OpenLibraryClient::quotedPhrase)
            .collect(Collectors.joining(" OR ", "subject:(", ")"));
    }

    // Package-private for the same reason as subjectQuery. The genre,
    // available-in-language, and page-count clauses are independent Solr
    // fields, so ANDing them narrows to their intersection exactly like
    // TMDB's with_genres, with_original_language, and with_runtime combining
    // in the same discover request.
    static String combinedQuery(List<String> genreSubjectAliases, String availableInLanguageMarc3, String pageCountRange) {
        var clauses = new ArrayList<String>();
        if (!genreSubjectAliases.isEmpty()) {
            clauses.add(subjectQuery(genreSubjectAliases));
        }
        if (availableInLanguageMarc3 != null) {
            clauses.add("language:" + availableInLanguageMarc3);
        }
        CatalogRange.parse(pageCountRange).ifPresent(range -> clauses.add(pageCountQuery(range)));
        return clauses.isEmpty() ? MATCH_ALL_QUERY : String.join(" AND ", clauses);
    }

    // ADR 0018: number_of_pages_median is a live-verified queryable Solr
    // range field despite being absent from OpenLibrary's documented
    // searchable-field list — "[min TO max]" is the same range-query idiom
    // the ADR's own live-verified example uses, with either bound replaced
    // by "*" for CatalogRange's open-ended side rather than omitting the
    // clause's bracket structure entirely.
    private static String pageCountQuery(CatalogRange range) {
        var min = range.min() != null ? range.min().toString() : "*";
        var max = range.max() != null ? range.max().toString() : "*";
        return "number_of_pages_median:[" + min + " TO " + max + "]";
    }

    private static String quotedPhrase(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
            case CatalogSort.POPULARITY -> "trending";
            case CatalogSort.RELEASE_DATE -> "new";
            case CatalogSort.TITLE -> "title";
            case CatalogSort.EXTERNAL_RATING -> "rating";
            default -> throw new IllegalArgumentException("Unsupported sort key: " + sortKey);
        };
        var defaultDirection = CatalogSort.TITLE.equals(sortKey) ? CatalogSort.ASCENDING : CatalogSort.DESCENDING;
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
