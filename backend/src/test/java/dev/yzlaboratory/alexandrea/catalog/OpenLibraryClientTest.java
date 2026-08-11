package dev.yzlaboratory.alexandrea.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Request shape and response mapping for OpenLibrary's
 * {@code /trending/daily.json} endpoint, against captured/mocked HTTP
 * responses rather than a live call (per the feature ticket's testing
 * decisions).
 */
class OpenLibraryClientTest {

    private static final String BASE_URL = "https://openlibrary.org";

    private MockRestServiceServer server;
    private OpenLibraryClient client;

    @BeforeEach
    void setUp() {
        var properties = new CatalogProperties(
            null, new CatalogProperties.OpenLibrary(BASE_URL, "https://covers.openlibrary.org/b/id/"), null);
        var builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenLibraryClient(builder.build(), properties);
    }

    @Test
    void mapsATrendingResponseIntoCatalogItems() {
        expectTrendingRequest().andRespond(withSuccess("""
            {
              "works": [
                {
                  "key": "/works/OL262758W",
                  "title": "Ready Player One",
                  "cover_i": 8235116,
                  "first_publish_year": 2011
                }
              ]
            }
            """, MediaType.APPLICATION_JSON));

        var result = client.trendingBooks(1);

        assertThat(result.items()).hasSize(1);
        var item = result.items().getFirst();
        assertThat(item.provider()).isEqualTo("OpenLibrary");
        assertThat(item.externalId()).isEqualTo("OL262758W");
        assertThat(item.mediaType()).isEqualTo("books");
        assertThat(item.title()).isEqualTo("Ready Player One");
        assertThat(item.coverUrl()).isEqualTo("https://covers.openlibrary.org/b/id/8235116-M.jpg");
        assertThat(item.releaseDate()).isEqualTo(LocalDate.of(2011, 1, 1));
        assertThat(item.externalRatingScale()).isEqualTo(5.0);
        assertThat(result.page()).isEqualTo(1);
    }

    @Test
    void externalRatingIsAlwaysNullSinceTrendingCarriesNoRatingField() {
        // /trending/daily.json exposes no community-rating field at all
        // (verified against the live endpoint) — OpenLibrary's real rating
        // lives behind a per-work /works/{id}/ratings.json call, deferred
        // to #48 rather than fetched inline here.
        expectTrendingRequest().andRespond(withSuccess("""
            {
              "works": [
                {"key": "/works/OL1W", "title": "A Book"}
              ]
            }
            """, MediaType.APPLICATION_JSON));

        var item = client.trendingBooks(1).items().getFirst();

        assertThat(item.externalRating()).isNull();
    }

    @Test
    void anUnexpectedRatingsAverageFieldInTheResponseIsIgnored() {
        // Guards against silently regressing to the earlier bug: reading a
        // "ratings_average" field the live endpoint never actually sends,
        // which made externalRating read as a checked-and-absent null
        // instead of the honestly-not-fetched null it is for now.
        expectTrendingRequest().andRespond(withSuccess("""
            {
              "works": [
                {"key": "/works/OL1W", "title": "A Book", "ratings_average": 4.2}
              ]
            }
            """, MediaType.APPLICATION_JSON));

        var item = client.trendingBooks(1).items().getFirst();

        assertThat(item.externalRating()).isNull();
    }

    @Test
    void worksWithNoKeyAreExcludedRatherThanCollidingUnderANullCacheKey() {
        expectTrendingRequest().andRespond(withSuccess("""
            {
              "works": [
                {"title": "No Key One"},
                {"title": "No Key Two"},
                {"key": "/works/OL9W", "title": "Has A Key"}
              ]
            }
            """, MediaType.APPLICATION_JSON));

        var result = client.trendingBooks(1);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().title()).isEqualTo("Has A Key");
    }

    @Test
    void aWorkWithNoCoverOrPublishYearMapsToNullFieldsRatherThanCrashing() {
        expectTrendingRequest().andRespond(withSuccess("""
            {
              "works": [
                {"key": "/works/OL3W", "title": "No Cover Or Year"}
              ]
            }
            """, MediaType.APPLICATION_JSON));

        var item = client.trendingBooks(1).items().getFirst();

        assertThat(item.coverUrl()).isNull();
        assertThat(item.releaseDate()).isNull();
        assertThat(item.externalRating()).isNull();
    }

    @Test
    void anEmptyWorksArrayMapsToAnEmptyListWithoutError() {
        expectTrendingRequest().andRespond(withSuccess("""
            {"works": []}
            """, MediaType.APPLICATION_JSON));

        var result = client.trendingBooks(1);

        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void aNullWorksFieldMapsToAnEmptyListRatherThanThrowing() {
        expectTrendingRequest().andRespond(withSuccess("""
            {"works": null}
            """, MediaType.APPLICATION_JSON));

        var result = client.trendingBooks(1);

        assertThat(result.items()).isEmpty();
    }

    @Test
    void aFullPageOfTwentyReportsMoreAvailable() {
        expectTrendingRequest().andRespond(withSuccess(fullPageOfWorksJson(), MediaType.APPLICATION_JSON));

        var result = client.trendingBooks(1);

        assertThat(result.items()).hasSize(20);
        assertThat(result.hasMore()).isTrue();
    }

    @Test
    void aShortPageReportsNoMoreAvailable() {
        expectTrendingRequest().andRespond(withSuccess("""
            {"works": [{"key": "/works/OL9W", "title": "Only One Left"}]}
            """, MediaType.APPLICATION_JSON));

        var result = client.trendingBooks(1);

        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void anUpstream5xxIsWrappedAsACatalogUpstreamException() {
        expectTrendingRequest().andRespond(withServerError());

        assertThatThrownBy(() -> client.trendingBooks(1)).isInstanceOf(CatalogUpstreamException.class);
    }

    @Test
    void requestsLimitAndOffsetComputedFromThePageNumber() {
        server.expect(requestTo(BASE_URL + "/trending/daily.json?limit=20&offset=40"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""
                {"works": []}
                """, MediaType.APPLICATION_JSON));

        client.trendingBooks(3);

        server.verify();
    }

    @Test
    void searchMapsAMatchingDocIntoCatalogItemsJustLikeTrending() {
        expectSearchRequest("q", "ready%20player%20one").andRespond(withSuccess("""
            {
              "docs": [
                {
                  "key": "/works/OL262758W",
                  "title": "Ready Player One",
                  "cover_i": 8235116,
                  "first_publish_year": 2011
                }
              ]
            }
            """, MediaType.APPLICATION_JSON));

        var result = client.search("ready player one", 1);

        assertThat(result.items()).hasSize(1);
        var item = result.items().getFirst();
        assertThat(item.provider()).isEqualTo("OpenLibrary");
        assertThat(item.externalId()).isEqualTo("OL262758W");
        assertThat(item.mediaType()).isEqualTo("books");
        assertThat(item.title()).isEqualTo("Ready Player One");
        assertThat(item.coverUrl()).isEqualTo("https://covers.openlibrary.org/b/id/8235116-M.jpg");
    }

    @Test
    void searchWithNoMatchesMapsToAnEmptyPageRatherThanAnError() {
        expectSearchRequest("q", "zzzznomatch").andRespond(withSuccess("""
            {"docs": []}
            """, MediaType.APPLICATION_JSON));

        var result = client.search("zzzznomatch", 1);

        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void aNullDocsFieldMapsToAnEmptyListRatherThanThrowing() {
        expectSearchRequest("q", "anything").andRespond(withSuccess("""
            {"docs": null}
            """, MediaType.APPLICATION_JSON));

        var result = client.search("anything", 1);

        assertThat(result.items()).isEmpty();
    }

    @Test
    void searchExcludesKeylessDocsJustLikeTrending() {
        expectSearchRequest("q", "anything").andRespond(withSuccess("""
            {
              "docs": [
                {"title": "No Key Book"},
                {"key": "/works/OL9W", "title": "Has A Key"}
              ]
            }
            """, MediaType.APPLICATION_JSON));

        var result = client.search("anything", 1);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().title()).isEqualTo("Has A Key");
    }

    @Test
    void anUpstream5xxOnSearchIsWrappedAsACatalogUpstreamException() {
        expectSearchRequest("q", "anything").andRespond(withServerError());

        assertThatThrownBy(() -> client.search("anything", 1)).isInstanceOf(CatalogUpstreamException.class);
    }

    @Test
    void searchRequestsLimitAndOffsetComputedFromThePageNumber() {
        server.expect(requestTo(BASE_URL + "/search.json?q=title&limit=20&offset=40"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""
                {"docs": []}
                """, MediaType.APPLICATION_JSON));

        client.search("title", 3);

        server.verify();
    }

    @Test
    void sortedBooksRequestsAWildcardQueryWithTheGivenSortAndTheRatingsAverageField() {
        expectSortedRequest("trending").andRespond(withSuccess("""
            {"docs": []}
            """, MediaType.APPLICATION_JSON));

        client.sortedBooks("popularity", "desc", 1);

        server.verify();
    }

    @Test
    void sortedBooksMapsReleaseDateDescendingToNew() {
        assertSortedBooksSortParam("release_date", "desc", "new");
    }

    @Test
    void sortedBooksMapsTitleAscendingToTitle() {
        assertSortedBooksSortParam("title", "asc", "title");
    }

    @Test
    void sortedBooksMapsExternalRatingDescendingToRating() {
        assertSortedBooksSortParam("external_rating", "desc", "rating");
    }

    @Test
    void sortedBooksHonorsAscendingPopularityAsTheOppositeOfTheAdr0018Default() {
        assertSortedBooksSortParam("popularity", "asc", "trending%20asc");
    }

    @Test
    void sortedBooksHonorsAscendingReleaseDateAsTheOppositeOfTheAdr0018Default() {
        assertSortedBooksSortParam("release_date", "asc", "new%20asc");
    }

    @Test
    void sortedBooksHonorsDescendingTitleAsTheOppositeOfTheAdr0018Default() {
        assertSortedBooksSortParam("title", "desc", "title%20desc");
    }

    @Test
    void sortedBooksHonorsAscendingExternalRatingAsTheOppositeOfTheAdr0018Default() {
        assertSortedBooksSortParam("external_rating", "asc", "rating%20asc");
    }

    @Test
    void sortedBooksRequestsLimitAndOffsetComputedFromThePageNumber() {
        server.expect(requestTo(BASE_URL + "/search.json?q=*&sort=trending&fields=key,title,cover_i,first_publish_year,ratings_average&limit=20&offset=40"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""
                {"docs": []}
                """, MediaType.APPLICATION_JSON));

        client.sortedBooks("popularity", "desc", 3);

        server.verify();
    }

    @Test
    void sortedBooksMapsAMatchingDocIntoCatalogItemsJustLikeTrending() {
        expectSortedRequest("title").andRespond(withSuccess("""
            {
              "docs": [
                {"key": "/works/OL262758W", "title": "Ready Player One", "cover_i": 8235116, "first_publish_year": 2011}
              ]
            }
            """, MediaType.APPLICATION_JSON));

        var result = client.sortedBooks("title", "asc", 1);

        assertThat(result.items()).hasSize(1);
        var item = result.items().getFirst();
        assertThat(item.provider()).isEqualTo("OpenLibrary");
        assertThat(item.externalId()).isEqualTo("OL262758W");
        assertThat(item.mediaType()).isEqualTo("books");
        assertThat(item.title()).isEqualTo("Ready Player One");
        assertThat(item.coverUrl()).isEqualTo("https://covers.openlibrary.org/b/id/8235116-M.jpg");
        assertThat(item.releaseDate()).isEqualTo(LocalDate.of(2011, 1, 1));
    }

    @Test
    void sortedBooksExcludesKeylessDocsJustLikeTrending() {
        expectSortedRequest("trending").andRespond(withSuccess("""
            {
              "docs": [
                {"title": "No Key Book"},
                {"key": "/works/OL9W", "title": "Has A Key"}
              ]
            }
            """, MediaType.APPLICATION_JSON));

        var result = client.sortedBooks("popularity", "desc", 1);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().title()).isEqualTo("Has A Key");
    }

    @Test
    void sortedBooksByAnythingOtherThanExternalRatingKeepsEntriesWithNoRating() {
        expectSortedRequest("trending").andRespond(withSuccess("""
            {
              "docs": [
                {"key": "/works/OL1W", "title": "Unrated Book"}
              ]
            }
            """, MediaType.APPLICATION_JSON));

        var result = client.sortedBooks("popularity", "desc", 1);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().externalRating()).isNull();
    }

    @Test
    void sortedBooksByExternalRatingExcludesEntriesWithNoRatingRatherThanSinkingOrZeroingThem() {
        expectSortedRequest("rating").andRespond(withSuccess("""
            {
              "docs": [
                {"key": "/works/OL1W", "title": "Rated Book", "ratings_average": 4.2},
                {"key": "/works/OL2W", "title": "Unrated Book"}
              ]
            }
            """, MediaType.APPLICATION_JSON));

        var result = client.sortedBooks("external_rating", "desc", 1);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().title()).isEqualTo("Rated Book");
        assertThat(result.items().getFirst().externalRating()).isEqualTo(4.2);
    }

    @Test
    void sortedBooksByExternalRatingReturningOnlyUnratedEntriesProducesAnEmptyPageRatherThanAnError() {
        expectSortedRequest("rating").andRespond(withSuccess("""
            {
              "docs": [
                {"key": "/works/OL1W", "title": "Unrated One"},
                {"key": "/works/OL2W", "title": "Unrated Two"}
              ]
            }
            """, MediaType.APPLICATION_JSON));

        var result = client.sortedBooks("external_rating", "desc", 1);

        assertThat(result.items()).isEmpty();
    }

    @Test
    void sortedBooksByExternalRatingStillReportsMoreAvailableWhenTheFullUpstreamPageWasFilteredToEmpty() {
        expectSortedRequest("rating").andRespond(withSuccess(fullPageOfUnratedDocsJson(), MediaType.APPLICATION_JSON));

        var result = client.sortedBooks("external_rating", "desc", 1);

        // The upstream page was full (20 keyed docs) even though every one of
        // them lacked a rating and got filtered out — hasMore must reflect
        // the former, not collapse to false just because this page's visible
        // result set emptied out (ADR 0006's shrink is a display concern,
        // not a pagination one).
        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isTrue();
    }

    @Test
    void aNullDocsFieldOnSortedBooksMapsToAnEmptyListRatherThanThrowing() {
        expectSortedRequest("trending").andRespond(withSuccess("""
            {"docs": null}
            """, MediaType.APPLICATION_JSON));

        var result = client.sortedBooks("popularity", "desc", 1);

        assertThat(result.items()).isEmpty();
    }

    @Test
    void anUpstream5xxOnSortedBooksIsWrappedAsACatalogUpstreamException() {
        expectSortedRequest("trending").andRespond(withServerError());

        assertThatThrownBy(() -> client.sortedBooks("popularity", "desc", 1))
            .isInstanceOf(CatalogUpstreamException.class);
    }

    private void assertSortedBooksSortParam(String sortKey, String direction, String expectedSortParam) {
        expectSortedRequest(expectedSortParam).andRespond(withSuccess("""
            {"docs": []}
            """, MediaType.APPLICATION_JSON));

        client.sortedBooks(sortKey, direction, 1);
    }

    private static String fullPageOfUnratedDocsJson() {
        var docs = new StringBuilder();
        for (var i = 1; i <= 20; i++) {
            if (i > 1) {
                docs.append(',');
            }
            docs.append("{\"key\": \"/works/OL").append(i).append("W\", \"title\": \"Work ").append(i).append("\"}");
        }
        return "{\"docs\": [" + docs + "]}";
    }

    private org.springframework.test.web.client.ResponseActions expectSortedRequest(String expectedSortParam) {
        return server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/search.json")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("q", "*"))
            .andExpect(queryParam("sort", expectedSortParam));
    }

    private org.springframework.test.web.client.ResponseActions expectTrendingRequest() {
        return server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/trending/daily.json")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("limit", "20"));
    }

    private org.springframework.test.web.client.ResponseActions expectSearchRequest(String queryParamName, String queryParamValue) {
        return server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/search.json")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam(queryParamName, queryParamValue));
    }

    private static String fullPageOfWorksJson() {
        var works = new StringBuilder();
        for (var i = 1; i <= 20; i++) {
            if (i > 1) {
                works.append(',');
            }
            works.append("{\"key\": \"/works/OL").append(i).append("W\", \"title\": \"Work ").append(i).append("\"}");
        }
        return "{\"works\": [" + works + "]}";
    }
}
