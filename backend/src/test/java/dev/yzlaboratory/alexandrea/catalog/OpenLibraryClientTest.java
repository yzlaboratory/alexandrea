package dev.yzlaboratory.alexandrea.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
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
    void aFullUpstreamPageWithAKeylessWorkStillReportsMoreAvailable() {
        expectTrendingRequest().andRespond(withSuccess(fullPageOfWorksJsonWithOneKeylessWork(), MediaType.APPLICATION_JSON));

        var result = client.trendingBooks(1);

        // The raw upstream page was full (20 works) even though one of them
        // lacked a key and got filtered out -- hasMore must reflect the raw
        // upstream count, not the post-keyless-filter item count, or a
        // genuinely full page under-reports as the end of the feed.
        assertThat(result.items()).hasSize(19);
        assertThat(result.hasMore()).isTrue();
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
    void searchRetriesOnceAfterATransient500ThenSucceeds() {
        expectSearchRequest("q", "anything").andRespond(withServerError());
        expectSearchRequest("q", "anything").andRespond(withSuccess("""
            {"docs": [{"key": "/works/OL1W", "title": "Recovered Book"}]}
            """, MediaType.APPLICATION_JSON));

        var result = client.search("anything", 1);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().title()).isEqualTo("Recovered Book");
        server.verify();
    }

    @Test
    void anUpstream5xxOnSearchIsRetriedAndEventuallyWrappedAsACatalogUpstreamException() {
        server.expect(ExpectedCount.times(3), requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/search.json")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("q", "anything"))
            .andRespond(withServerError());

        assertThatThrownBy(() -> client.search("anything", 1)).isInstanceOf(CatalogUpstreamException.class);

        server.verify();
    }

    @Test
    void searchDoesNotRetryOnANonServerErrorFailure() {
        expectSearchRequest("q", "anything").andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.search("anything", 1)).isInstanceOf(CatalogUpstreamException.class);

        server.verify();
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

        client.sortedBooks("popularity", "desc", List.of(), null, null, 1);

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

        client.sortedBooks("popularity", "desc", List.of(), null, null, 3);

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

        var result = client.sortedBooks("title", "asc", List.of(), null, null, 1);

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

        var result = client.sortedBooks("popularity", "desc", List.of(), null, null, 1);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().title()).isEqualTo("Has A Key");
    }

    @Test
    void sortedBooksWithAFullUpstreamPageContainingAKeylessDocStillReportsMoreAvailable() {
        expectSortedRequest("trending").andRespond(withSuccess(fullPageOfDocsJsonWithOneKeylessDoc(), MediaType.APPLICATION_JSON));

        var result = client.sortedBooks("popularity", "desc", List.of(), null, null, 1);

        // Mirrors aFullUpstreamPageWithAKeylessWorkStillReportsMoreAvailable
        // for the sorted/discover path: the raw upstream page was full (20
        // docs) even though one lacked a key and got filtered out --
        // hasMore must reflect the raw upstream count, not the
        // post-keyless-filter one.
        assertThat(result.items()).hasSize(19);
        assertThat(result.hasMore()).isTrue();
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

        var result = client.sortedBooks("popularity", "desc", List.of(), null, null, 1);

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

        var result = client.sortedBooks("external_rating", "desc", List.of(), null, null, 1);

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

        var result = client.sortedBooks("external_rating", "desc", List.of(), null, null, 1);

        assertThat(result.items()).isEmpty();
    }

    @Test
    void sortedBooksByExternalRatingStillReportsMoreAvailableWhenTheFullUpstreamPageWasFilteredToEmpty() {
        expectSortedRequest("rating").andRespond(withSuccess(fullPageOfUnratedDocsJson(), MediaType.APPLICATION_JSON));

        var result = client.sortedBooks("external_rating", "desc", List.of(), null, null, 1);

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

        var result = client.sortedBooks("popularity", "desc", List.of(), null, null, 1);

        assertThat(result.items()).isEmpty();
    }

    @Test
    void sortedBooksRetriesOnceAfterATransient500ThenSucceeds() {
        expectSortedRequest("trending").andRespond(withServerError());
        expectSortedRequest("trending").andRespond(withSuccess("""
            {"docs": [{"key": "/works/OL1W", "title": "Recovered Book"}]}
            """, MediaType.APPLICATION_JSON));

        var result = client.sortedBooks("popularity", "desc", List.of(), null, null, 1);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().title()).isEqualTo("Recovered Book");
        server.verify();
    }

    @Test
    void anUpstream5xxOnSortedBooksIsRetriedAndEventuallyWrappedAsACatalogUpstreamException() {
        server.expect(ExpectedCount.times(3), requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/search.json")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("q", "*"))
            .andExpect(queryParam("sort", "trending"))
            .andRespond(withServerError());

        assertThatThrownBy(() -> client.sortedBooks("popularity", "desc", List.of(), null, null, 1))
            .isInstanceOf(CatalogUpstreamException.class);

        server.verify();
    }

    @Test
    void sortedBooksDoesNotRetryOnANonServerErrorFailure() {
        expectSortedRequest("trending").andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.sortedBooks("popularity", "desc", List.of(), null, null, 1))
            .isInstanceOf(CatalogUpstreamException.class);

        server.verify();
    }

    @Test
    void sortedBooksWithACuratedGenreReplacesTheWildcardQueryWithASubjectOrClause() {
        var aliases = List.of("Science fiction", "Sci-Fi");
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/search.json")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("q", "subject:(%22Science%20fiction%22%20OR%20%22Sci-Fi%22)"))
            .andExpect(queryParam("sort", "trending"))
            .andRespond(withSuccess("""
                {"docs": []}
                """, MediaType.APPLICATION_JSON));

        client.sortedBooks("popularity", "desc", aliases, null, null, 1);

        server.verify();
    }

    @Test
    void sortedBooksWithNoGenreStillSendsTheWildcardQuery() {
        expectSortedRequest("trending").andRespond(withSuccess("""
            {"docs": []}
            """, MediaType.APPLICATION_JSON));

        client.sortedBooks("popularity", "desc", List.of(), null, null, 1);

        server.verify();
    }

    @Test
    void sortedBooksWithACuratedGenreMapsAMatchingDocIntoCatalogItems() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/search.json")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("sort", "trending"))
            .andRespond(withSuccess("""
                {"docs": [{"key": "/works/OL1W", "title": "A Sci-Fi Book"}]}
                """, MediaType.APPLICATION_JSON));

        var result = client.sortedBooks("popularity", "desc", List.of("Sci-Fi"), null, null, 1);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().title()).isEqualTo("A Sci-Fi Book");
    }

    @Test
    void sortedBooksWithAnAvailableInLanguageReplacesTheWildcardQueryWithALanguageClause() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/search.json")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("q", "language:ger"))
            .andExpect(queryParam("sort", "trending"))
            .andRespond(withSuccess("""
                {"docs": []}
                """, MediaType.APPLICATION_JSON));

        client.sortedBooks("popularity", "desc", List.of(), "ger", null, 1);

        server.verify();
    }

    @Test
    void sortedBooksCombinesGenreAndAvailableInLanguageWithAndWhenBothAreGiven() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/search.json")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("q", "subject:(%22Sci-Fi%22)%20AND%20language:ger"))
            .andRespond(withSuccess("""
                {"docs": []}
                """, MediaType.APPLICATION_JSON));

        client.sortedBooks("popularity", "desc", List.of("Sci-Fi"), "ger", null, 1);

        server.verify();
    }

    // OpenLibrary's language field on a work reflects every edition's
    // language, not the work's original language — so this German-tagged
    // result must surface even though the doc itself carries no evidence of
    // being originally German, matching ADR 0018's "has an edition in this
    // language" semantics rather than "was originally in this language".
    @Test
    void sortedBooksWithAnAvailableInLanguageMapsAMatchingDocRegardlessOfItsOriginalLanguage() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/search.json")))
            .andExpect(queryParam("q", "language:ger"))
            .andRespond(withSuccess("""
                {"docs": [{"key": "/works/OL1W", "title": "Dune"}]}
                """, MediaType.APPLICATION_JSON));

        var result = client.sortedBooks("popularity", "desc", List.of(), "ger", null, 1);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().title()).isEqualTo("Dune");
    }

    @Test
    void sortedBooksWithAPageCountRangeReplacesTheWildcardQueryWithANumberOfPagesMedianClause() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/search.json")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("q", "number_of_pages_median:%5B300%20TO%20400%5D"))
            .andExpect(queryParam("sort", "trending"))
            .andRespond(withSuccess("""
                {"docs": []}
                """, MediaType.APPLICATION_JSON));

        client.sortedBooks("popularity", "desc", List.of(), null, "300,400", 1);

        server.verify();
    }

    @Test
    void sortedBooksWithAnOpenEndedMinOnlyPageCountRangeUsesAWildcardMax() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/search.json")))
            .andExpect(queryParam("q", "number_of_pages_median:%5B300%20TO%20*%5D"))
            .andRespond(withSuccess("""
                {"docs": []}
                """, MediaType.APPLICATION_JSON));

        client.sortedBooks("popularity", "desc", List.of(), null, "300,", 1);

        server.verify();
    }

    @Test
    void sortedBooksWithAnOpenEndedMaxOnlyPageCountRangeUsesAWildcardMin() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/search.json")))
            .andExpect(queryParam("q", "number_of_pages_median:%5B*%20TO%20400%5D"))
            .andRespond(withSuccess("""
                {"docs": []}
                """, MediaType.APPLICATION_JSON));

        client.sortedBooks("popularity", "desc", List.of(), null, ",400", 1);

        server.verify();
    }

    @Test
    void sortedBooksWithAMinEqualsMaxPageCountRangeStillSendsTheClause() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/search.json")))
            .andExpect(queryParam("q", "number_of_pages_median:%5B300%20TO%20300%5D"))
            .andRespond(withSuccess("""
                {"docs": []}
                """, MediaType.APPLICATION_JSON));

        client.sortedBooks("popularity", "desc", List.of(), null, "300,300", 1);

        server.verify();
    }

    @Test
    void sortedBooksWithAMalformedPageCountRangeFallsBackToTheWildcardQueryRatherThanThrowing() {
        expectSortedRequest("trending").andRespond(withSuccess("""
            {"docs": []}
            """, MediaType.APPLICATION_JSON));

        client.sortedBooks("popularity", "desc", List.of(), null, "not-a-range", 1);

        server.verify();
    }

    @Test
    void sortedBooksWithAnInvertedPageCountRangeFallsBackToTheWildcardQueryRatherThanThrowing() {
        expectSortedRequest("trending").andRespond(withSuccess("""
            {"docs": []}
            """, MediaType.APPLICATION_JSON));

        client.sortedBooks("popularity", "desc", List.of(), null, "400,300", 1);

        server.verify();
    }

    @Test
    void sortedBooksCombinesGenreAvailableInLanguageAndPageCountWithAndWhenAllThreeAreGiven() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/search.json")))
            .andExpect(queryParam("q", "subject:(%22Sci-Fi%22)%20AND%20language:ger%20AND%20number_of_pages_median:%5B300%20TO%20400%5D"))
            .andRespond(withSuccess("""
                {"docs": []}
                """, MediaType.APPLICATION_JSON));

        client.sortedBooks("popularity", "desc", List.of("Sci-Fi"), "ger", "300,400", 1);

        server.verify();
    }

    @Test
    void sortedBooksWithAPageCountRangeMapsAMatchingDocIntoCatalogItems() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/search.json")))
            .andExpect(queryParam("q", "number_of_pages_median:%5B300%20TO%20400%5D"))
            .andRespond(withSuccess("""
                {"docs": [{"key": "/works/OL1W", "title": "A Medium-Length Book"}]}
                """, MediaType.APPLICATION_JSON));

        var result = client.sortedBooks("popularity", "desc", List.of(), null, "300,400", 1);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().title()).isEqualTo("A Medium-Length Book");
    }

    // --- Issue 47: ADR 0018's "text search active" row for Books —
    // sortedBooks' text-query overload joins a title query with the same
    // sort=/field-filter clauses the plain overload above already sends,
    // proving OpenLibrary's one /search.json endpoint never needed a
    // separate, more restricted search path the way TMDB/IGDB do.

    @Test
    void sortedBooksWithATextQueryAndNoFiltersSendsJustTheQuery() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/search.json")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("q", "dune"))
            .andExpect(queryParam("sort", "trending"))
            .andRespond(withSuccess("""
                {"docs": []}
                """, MediaType.APPLICATION_JSON));

        client.sortedBooks("dune", "popularity", "desc", List.of(), null, null, 1);

        server.verify();
    }

    @Test
    void sortedBooksWithATextQueryAndAGenreJoinsThemWithAnd() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/search.json")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("q", "dune%20AND%20subject:(%22Sci-Fi%22)"))
            .andRespond(withSuccess("""
                {"docs": []}
                """, MediaType.APPLICATION_JSON));

        client.sortedBooks("dune", "popularity", "desc", List.of("Sci-Fi"), null, null, 1);

        server.verify();
    }

    @Test
    void sortedBooksWithATextQueryStillAppliesTheGivenSort() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/search.json")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("q", "dune"))
            .andExpect(queryParam("sort", "title"))
            .andRespond(withSuccess("""
                {"docs": []}
                """, MediaType.APPLICATION_JSON));

        client.sortedBooks("dune", "title", "asc", List.of(), null, null, 1);

        server.verify();
    }

    @Test
    void sortedBooksWithATextQueryAndAllThreeFilterKindsAndsEveryClauseTogether() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/search.json")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam(
                "q", "dune%20AND%20subject:(%22Sci-Fi%22)%20AND%20language:ger%20AND%20number_of_pages_median:%5B300%20TO%20400%5D"
            ))
            .andRespond(withSuccess("""
                {"docs": []}
                """, MediaType.APPLICATION_JSON));

        client.sortedBooks("dune", "popularity", "desc", List.of("Sci-Fi"), "ger", "300,400", 1);

        server.verify();
    }

    @Test
    void sortedBooksWithATextQueryMapsAMatchingDocIntoCatalogItems() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/search.json")))
            .andExpect(queryParam("q", "dune"))
            .andRespond(withSuccess("""
                {"docs": [{"key": "/works/OL1W", "title": "Dune", "ratings_average": 4.5}]}
                """, MediaType.APPLICATION_JSON));

        var result = client.sortedBooks("dune", "popularity", "desc", List.of(), null, null, 1);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().title()).isEqualTo("Dune");
        assertThat(result.items().getFirst().externalRating()).isEqualTo(4.5);
    }

    @Test
    void sortedBooksWithATextQueryAndExternalRatingSortStillExcludesUnratedEntries() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/search.json")))
            .andExpect(queryParam("q", "dune"))
            .andExpect(queryParam("sort", "rating"))
            .andRespond(withSuccess("""
                {
                  "docs": [
                    {"key": "/works/OL1W", "title": "Rated Dune Edition", "ratings_average": 4.5},
                    {"key": "/works/OL2W", "title": "Unrated Dune Edition"}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        var result = client.sortedBooks("dune", "external_rating", "desc", List.of(), null, null, 1);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().title()).isEqualTo("Rated Dune Edition");
    }

    @Test
    void sortedBooksWithATextQueryRequestsLimitAndOffsetComputedFromThePageNumber() {
        server.expect(requestTo(BASE_URL
                + "/search.json?q=dune&sort=trending&fields=key,title,cover_i,first_publish_year,ratings_average&limit=20&offset=40"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""
                {"docs": []}
                """, MediaType.APPLICATION_JSON));

        client.sortedBooks("dune", "popularity", "desc", List.of(), null, null, 3);

        server.verify();
    }

    @Test
    void sortedBooksWithANullTextQueryBehavesExactlyLikeTheSixArgOverload() {
        expectSortedRequest("trending").andRespond(withSuccess("""
            {"docs": []}
            """, MediaType.APPLICATION_JSON));

        client.sortedBooks(null, "popularity", "desc", List.of(), null, null, 1);

        server.verify();
    }

    @Test
    void subjectQueryPhraseQuotesEachAliasAndJoinsWithOr() {
        assertThat(OpenLibraryClient.subjectQuery(List.of("Science fiction", "Sci-Fi")))
            .isEqualTo("subject:(\"Science fiction\" OR \"Sci-Fi\")");
    }

    @Test
    void combinedQueryWithNeitherGenreNorLanguageNorPageCountIsTheWildcard() {
        assertThat(OpenLibraryClient.combinedQuery(List.of(), null, null)).isEqualTo("*");
    }

    @Test
    void combinedQueryWithOnlyGenreIsJustTheSubjectClause() {
        assertThat(OpenLibraryClient.combinedQuery(List.of("Sci-Fi"), null, null)).isEqualTo("subject:(\"Sci-Fi\")");
    }

    @Test
    void combinedQueryWithOnlyLanguageIsJustTheLanguageClause() {
        assertThat(OpenLibraryClient.combinedQuery(List.of(), "ger", null)).isEqualTo("language:ger");
    }

    @Test
    void combinedQueryWithGenreAndLanguageAndsTheTwoClauses() {
        assertThat(OpenLibraryClient.combinedQuery(List.of("Sci-Fi"), "ger", null)).isEqualTo("subject:(\"Sci-Fi\") AND language:ger");
    }

    @Test
    void combinedQueryWithOnlyAFullPageCountRangeIsJustTheNumberOfPagesClause() {
        assertThat(OpenLibraryClient.combinedQuery(List.of(), null, "300,400"))
            .isEqualTo("number_of_pages_median:[300 TO 400]");
    }

    @Test
    void combinedQueryWithAnOpenEndedMinOnlyPageCountRangeUsesAWildcardMax() {
        assertThat(OpenLibraryClient.combinedQuery(List.of(), null, "300,"))
            .isEqualTo("number_of_pages_median:[300 TO *]");
    }

    @Test
    void combinedQueryWithAnOpenEndedMaxOnlyPageCountRangeUsesAWildcardMin() {
        assertThat(OpenLibraryClient.combinedQuery(List.of(), null, ",400"))
            .isEqualTo("number_of_pages_median:[* TO 400]");
    }

    @Test
    void combinedQueryWithAMinEqualsMaxPageCountRangeIsAOneValueBracket() {
        assertThat(OpenLibraryClient.combinedQuery(List.of(), null, "300,300"))
            .isEqualTo("number_of_pages_median:[300 TO 300]");
    }

    @Test
    void combinedQueryWithAMalformedPageCountRangeOmitsTheClauseRatherThanThrowing() {
        assertThat(OpenLibraryClient.combinedQuery(List.of(), null, "not-a-range")).isEqualTo("*");
    }

    @Test
    void combinedQueryWithAnInvertedPageCountRangeOmitsTheClauseRatherThanThrowing() {
        assertThat(OpenLibraryClient.combinedQuery(List.of(), null, "400,300")).isEqualTo("*");
    }

    @Test
    void combinedQueryAndsAllThreeClausesWhenGenreLanguageAndPageCountAreAllGiven() {
        assertThat(OpenLibraryClient.combinedQuery(List.of("Sci-Fi"), "ger", "300,400"))
            .isEqualTo("subject:(\"Sci-Fi\") AND language:ger AND number_of_pages_median:[300 TO 400]");
    }

    @Test
    void subjectQueryWithASingleAliasNeedsNoOr() {
        assertThat(OpenLibraryClient.subjectQuery(List.of("Horror"))).isEqualTo("subject:(\"Horror\")");
    }

    @Test
    void subjectQueryEscapesEmbeddedQuotesAndBackslashesInAnAlias() {
        assertThat(OpenLibraryClient.subjectQuery(List.of("Weird \"Alias\" \\ Name")))
            .isEqualTo("subject:(\"Weird \\\"Alias\\\" \\\\ Name\")");
    }

    private void assertSortedBooksSortParam(String sortKey, String direction, String expectedSortParam) {
        expectSortedRequest(expectedSortParam).andRespond(withSuccess("""
            {"docs": []}
            """, MediaType.APPLICATION_JSON));

        client.sortedBooks(sortKey, direction, List.of(), null, null, 1);
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

    // 20 raw docs total: one keyless (filtered out before hasMore's raw-size
    // check per the fix under test) plus 19 keyed ones.
    private static String fullPageOfDocsJsonWithOneKeylessDoc() {
        var docs = new StringBuilder("{\"title\": \"No Key Book\"}");
        for (var i = 1; i <= 19; i++) {
            docs.append(',').append("{\"key\": \"/works/OL").append(i).append("W\", \"title\": \"Work ").append(i).append("\"}");
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

    // 20 raw works total: one keyless (filtered out before hasMore's
    // raw-size check per the fix under test) plus 19 keyed ones.
    private static String fullPageOfWorksJsonWithOneKeylessWork() {
        var works = new StringBuilder("{\"title\": \"No Key Book\"}");
        for (var i = 1; i <= 19; i++) {
            works.append(',').append("{\"key\": \"/works/OL").append(i).append("W\", \"title\": \"Work ").append(i).append("\"}");
        }
        return "{\"works\": [" + works + "]}";
    }
}
