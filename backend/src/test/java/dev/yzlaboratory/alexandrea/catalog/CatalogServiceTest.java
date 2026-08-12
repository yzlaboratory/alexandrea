package dev.yzlaboratory.alexandrea.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.yzlaboratory.alexandrea.auth.MutableClock;
import dev.yzlaboratory.alexandrea.surface.SurfacePreference;
import dev.yzlaboratory.alexandrea.surface.SurfacePreferenceStore;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    private static final CatalogItem ITEM =
        new CatalogItem("TMDB", "1", "movies", "Title", "cover", LocalDate.of(2024, 1, 1), 7.5, 10.0);
    private static final Duration OPEN_WINDOW = Duration.ofSeconds(60);
    private static final Map<String, String> NO_FILTERS = Map.of();

    @Mock
    private TmdbClient tmdbClient;

    @Mock
    private OpenLibraryClient openLibraryClient;

    @Mock
    private IgdbClient igdbClient;

    @Mock
    private SurfacePreferenceStore surfacePreferenceStore;

    @Mock
    private GenreVocabulary genreVocabulary;

    @Mock
    private LanguageVocabulary languageVocabulary;

    private MutableTicker ticker;
    private MutableClock clock;
    private CatalogService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ticker = new MutableTicker();
        clock = new MutableClock(Instant.parse("2026-06-07T12:00:00Z"));
        var cache = new CatalogCache(ticker);
        var circuitBreaker = new ProviderCircuitBreaker(clock);
        service = new CatalogService(
            tmdbClient, openLibraryClient, igdbClient, cache, circuitBreaker, surfacePreferenceStore, genreVocabulary,
            languageVocabulary, objectMapper
        );
    }

    @Test
    void aColdCacheFetchesFromTmdbAndReturnsTheFetchedPage() {
        var page = new CatalogPageResult(List.of(ITEM), 1, true);
        when(tmdbClient.popularMovies(1)).thenReturn(page);

        var result = service.browse("movies", 1);

        assertThat(result).isEqualTo(page);
        verify(tmdbClient, times(1)).popularMovies(1);
    }

    @Test
    void aWarmCacheSkipsTheUpstreamCallEntirely() {
        var page = new CatalogPageResult(List.of(ITEM), 1, true);
        when(tmdbClient.popularMovies(1)).thenReturn(page);
        service.browse("movies", 1);

        var second = service.browse("movies", 1);

        assertThat(second).isEqualTo(page);
        verify(tmdbClient, times(1)).popularMovies(1);
    }

    @Test
    void differentPagesAreCachedIndependentlyAndBothHitTheProviderOnce() {
        var page1 = new CatalogPageResult(List.of(ITEM), 1, true);
        var page2 = new CatalogPageResult(List.of(), 2, false);
        when(tmdbClient.popularMovies(1)).thenReturn(page1);
        when(tmdbClient.popularMovies(2)).thenReturn(page2);

        assertThat(service.browse("movies", 1)).isEqualTo(page1);
        assertThat(service.browse("movies", 2)).isEqualTo(page2);
        // Re-requesting page 1 must not cost a second upstream call.
        assertThat(service.browse("movies", 1)).isEqualTo(page1);

        verify(tmdbClient, times(1)).popularMovies(1);
        verify(tmdbClient, times(1)).popularMovies(2);
    }

    @Test
    void anUnsupportedMediaTypeIsRejectedWithoutCallingAnyProvider() {
        assertThatThrownBy(() -> service.browse("podcasts", 1))
            .isInstanceOf(UnsupportedCatalogMediaTypeException.class);

        verifyNoInteractions(tmdbClient, openLibraryClient, igdbClient);
    }

    @Test
    void anEmptyMediaTypeIsAlsoRejectedRatherThanMatchingAnySwitchCase() {
        assertThatThrownBy(() -> service.browse("", 1))
            .isInstanceOf(UnsupportedCatalogMediaTypeException.class);

        verify(tmdbClient, never()).popularMovies(anyInt());
    }

    @Test
    void anEmptyUpstreamPageIsCachedAndReturnedAsIsOnASecondRequest() {
        var empty = new CatalogPageResult(List.of(), 50, false);
        when(tmdbClient.popularMovies(50)).thenReturn(empty);

        var first = service.browse("movies", 50);
        var second = service.browse("movies", 50);

        assertThat(first.items()).isEmpty();
        assertThat(second.items()).isEmpty();
        verify(tmdbClient, times(1)).popularMovies(50);
    }

    @Test
    void aColdMissOnUpstreamFailureThrowsRatherThanFabricatingAResult() {
        when(tmdbClient.popularMovies(1))
            .thenThrow(new CatalogUpstreamException("TMDB", new RuntimeException("boom")));

        assertThatThrownBy(() -> service.browse("movies", 1)).isInstanceOf(CatalogUpstreamException.class);
    }

    @Test
    void aWarmButExpiredPageIsServedStaleOnAnUpstreamFailureInsteadOfThrowing() {
        var page = new CatalogPageResult(List.of(ITEM), 1, true);
        when(tmdbClient.popularMovies(1)).thenReturn(page);
        service.browse("movies", 1);
        ticker.advance(Duration.ofDays(7).plusSeconds(1));
        when(tmdbClient.popularMovies(1))
            .thenThrow(new CatalogUpstreamException("TMDB", new RuntimeException("boom")));

        var result = service.browse("movies", 1);

        assertThat(result).isEqualTo(page);
    }

    @Test
    void theCircuitBreakerOpensAfterFiveConsecutiveFailuresAcrossDifferentPages() {
        failFivePages();

        // A sixth, still never-cached page must not reach TmdbClient at all —
        // the breaker is scoped to the provider, not to any one page.
        assertThatThrownBy(() -> service.browse("movies", 6)).isInstanceOf(CatalogUpstreamException.class);

        verify(tmdbClient, times(5)).popularMovies(anyInt());
    }

    @Test
    void anOpenBreakerStillServesAStalePageWithoutCallingTmdbAgain() {
        var page = new CatalogPageResult(List.of(ITEM), 1, true);
        when(tmdbClient.popularMovies(1)).thenReturn(page);
        service.browse("movies", 1);
        ticker.advance(Duration.ofDays(7).plusSeconds(1));

        failFivePages(2); // opens the breaker without ever touching page 1 again
        var result = service.browse("movies", 1);

        assertThat(result).isEqualTo(page);
        verify(tmdbClient, times(1)).popularMovies(1);
    }

    @Test
    void theBreakerShortCircuitsForTheWholeOpenWindow() {
        failFivePages();

        clock.advance(OPEN_WINDOW.minusSeconds(1));

        assertThatThrownBy(() -> service.browse("movies", 100)).isInstanceOf(CatalogUpstreamException.class);
        verify(tmdbClient, never()).popularMovies(100);
    }

    @Test
    void theBreakerAdmitsExactlyOneProbeAfterTheOpenWindowElapses() {
        failFivePages();
        clock.advance(OPEN_WINDOW.plusSeconds(1));
        when(tmdbClient.popularMovies(200))
            .thenThrow(new CatalogUpstreamException("TMDB", new RuntimeException("boom")));

        assertThatThrownBy(() -> service.browse("movies", 200)).isInstanceOf(CatalogUpstreamException.class);
        // Still inside the same (now re-opened) window — a second call must
        // not get a second probe.
        assertThatThrownBy(() -> service.browse("movies", 201)).isInstanceOf(CatalogUpstreamException.class);

        verify(tmdbClient, times(1)).popularMovies(200);
        verify(tmdbClient, never()).popularMovies(201);
    }

    @Test
    void aSuccessfulProbeClosesTheBreakerAndRestoresNormalCalls() {
        failFivePages();
        clock.advance(OPEN_WINDOW.plusSeconds(1));
        var probePage = new CatalogPageResult(List.of(ITEM), 300, true);
        when(tmdbClient.popularMovies(300)).thenReturn(probePage);

        var result = service.browse("movies", 300);

        assertThat(result).isEqualTo(probePage);
        var nextPage = new CatalogPageResult(List.of(ITEM), 301, true);
        when(tmdbClient.popularMovies(301)).thenReturn(nextPage);
        assertThat(service.browse("movies", 301)).isEqualTo(nextPage);
    }

    @Test
    void aFailedProbeReopensTheBreakerForAnotherWindow() {
        failFivePages();
        clock.advance(OPEN_WINDOW.plusSeconds(1));
        when(tmdbClient.popularMovies(400))
            .thenThrow(new CatalogUpstreamException("TMDB", new RuntimeException("boom")));
        assertThatThrownBy(() -> service.browse("movies", 400)).isInstanceOf(CatalogUpstreamException.class);

        clock.advance(OPEN_WINDOW.minusSeconds(1));

        assertThatThrownBy(() -> service.browse("movies", 401)).isInstanceOf(CatalogUpstreamException.class);
        verify(tmdbClient, never()).popularMovies(401);
    }

    @Test
    void anUnexpectedExceptionFromTheProviderStillSurfacesAsUpstreamUnavailableRatherThanRaw() {
        // TmdbClient is documented to wrap every failure as
        // CatalogUpstreamException, but nothing enforces that for it or for a
        // future provider client — the service itself must not assume it.
        when(tmdbClient.popularMovies(1)).thenThrow(new IllegalStateException("unexpected"));

        assertThatThrownBy(() -> service.browse("movies", 1)).isInstanceOf(CatalogUpstreamException.class);
    }

    @Test
    void anUnexpectedExceptionFromTheProviderStillCountsTowardTheBreakerThreshold() {
        var unexpected = new IllegalStateException("unexpected");
        for (var page = 1; page <= 4; page++) {
            var currentPage = page;
            when(tmdbClient.popularMovies(currentPage)).thenThrow(unexpected);
            assertThatThrownBy(() -> service.browse("movies", currentPage)).isInstanceOf(CatalogUpstreamException.class);
        }
        when(tmdbClient.popularMovies(5))
            .thenThrow(new CatalogUpstreamException("TMDB", new RuntimeException("boom")));
        assertThatThrownBy(() -> service.browse("movies", 5)).isInstanceOf(CatalogUpstreamException.class);

        // The 5th failure (a mix of exception types) must still have opened
        // the breaker — a 6th, never-cached page must not reach TmdbClient.
        assertThatThrownBy(() -> service.browse("movies", 6)).isInstanceOf(CatalogUpstreamException.class);
        verify(tmdbClient, never()).popularMovies(6);
    }

    @Test
    void aProbeThatThrowsAnUnexpectedExceptionStillReopensRatherThanWedgingTheBreakerForever() {
        failFivePages();
        clock.advance(OPEN_WINDOW.plusSeconds(1));
        when(tmdbClient.popularMovies(500)).thenThrow(new IllegalStateException("unexpected"));
        assertThatThrownBy(() -> service.browse("movies", 500)).isInstanceOf(CatalogUpstreamException.class);

        // Without recording this as a breaker failure, the claimed probe
        // would never be released and every later call — no matter how much
        // time passes — would short-circuit forever.
        clock.advance(OPEN_WINDOW.plusSeconds(1));
        var recovered = new CatalogPageResult(List.of(ITEM), 501, true);
        when(tmdbClient.popularMovies(501)).thenReturn(recovered);

        assertThat(service.browse("movies", 501)).isEqualTo(recovered);
    }

    @Test
    void tvRoutesToTmdbPopularTvRatherThanPopularMovies() {
        var tvItem = new CatalogItem("TMDB", "2", "tv", "A Series", "cover", LocalDate.of(2020, 1, 1), 8.0, 10.0);
        var page = new CatalogPageResult(List.of(tvItem), 1, true);
        when(tmdbClient.popularTv(1)).thenReturn(page);

        var result = service.browse("tv", 1);

        assertThat(result).isEqualTo(page);
        verify(tmdbClient, times(1)).popularTv(1);
        verify(tmdbClient, never()).popularMovies(anyInt());
    }

    @Test
    void moviesAndTvAreCachedUnderDistinctPagesDespiteSharingTheTmdbProvider() {
        var movieItem = ITEM;
        var tvItem = new CatalogItem("TMDB", "2", "tv", "A Series", "cover", LocalDate.of(2020, 1, 1), 8.0, 10.0);
        when(tmdbClient.popularMovies(1)).thenReturn(new CatalogPageResult(List.of(movieItem), 1, true));
        when(tmdbClient.popularTv(1)).thenReturn(new CatalogPageResult(List.of(tvItem), 1, true));

        var movies = service.browse("movies", 1);
        var tv = service.browse("tv", 1);

        assertThat(movies.items()).containsExactly(movieItem);
        assertThat(tv.items()).containsExactly(tvItem);
    }

    @Test
    void booksRoutesToOpenLibraryTrendingBooks() {
        var bookItem = new CatalogItem("OpenLibrary", "OL1W", "books", "A Book", "cover", null, 4.2, 5.0);
        var page = new CatalogPageResult(List.of(bookItem), 1, true);
        when(openLibraryClient.trendingBooks(1)).thenReturn(page);

        var result = service.browse("books", 1);

        assertThat(result).isEqualTo(page);
        verify(openLibraryClient, times(1)).trendingBooks(1);
        verifyNoInteractions(tmdbClient, igdbClient);
    }

    @Test
    void gamesRoutesToIgdbPopularGames() {
        var gameItem = new CatalogItem("IGDB", "42", "games", "A Game", "cover", null, 84.0, 100.0);
        var page = new CatalogPageResult(List.of(gameItem), 1, true);
        when(igdbClient.popularGames(1)).thenReturn(page);

        var result = service.browse("games", 1);

        assertThat(result).isEqualTo(page);
        verify(igdbClient, times(1)).popularGames(1);
        verifyNoInteractions(tmdbClient, openLibraryClient);
    }

    @Test
    void aBooksUpstreamFailureFallsThroughToAStaleCachedPageJustLikeMovies() {
        var bookItem = new CatalogItem("OpenLibrary", "OL1W", "books", "A Book", "cover", null, 4.2, 5.0);
        var page = new CatalogPageResult(List.of(bookItem), 1, true);
        when(openLibraryClient.trendingBooks(1)).thenReturn(page);
        service.browse("books", 1);
        ticker.advance(Duration.ofDays(7).plusSeconds(1));
        when(openLibraryClient.trendingBooks(1))
            .thenThrow(new CatalogUpstreamException("OpenLibrary", new RuntimeException("boom")));

        var result = service.browse("books", 1);

        assertThat(result).isEqualTo(page);
    }

    @Test
    void eachProviderHasItsOwnIndependentCircuitBreaker() {
        // Five OpenLibrary failures must not affect TMDB's breaker — the
        // breaker map is keyed by provider name, not shared globally.
        var openLibraryFailure = new CatalogUpstreamException("OpenLibrary", new RuntimeException("boom"));
        for (var page = 1; page <= 5; page++) {
            var currentPage = page;
            when(openLibraryClient.trendingBooks(currentPage)).thenThrow(openLibraryFailure);
            assertThatThrownBy(() -> service.browse("books", currentPage)).isInstanceOf(CatalogUpstreamException.class);
        }

        var moviePage = new CatalogPageResult(List.of(ITEM), 1, true);
        when(tmdbClient.popularMovies(1)).thenReturn(moviePage);
        assertThat(service.browse("movies", 1)).isEqualTo(moviePage);
    }

    @Test
    void fiveTvFailuresOpenTheSharedTmdbBreakerAndAlsoBlockMovies() {
        var tvFailure = new CatalogUpstreamException("TMDB", new RuntimeException("boom"));
        for (var page = 1; page <= 5; page++) {
            var currentPage = page;
            when(tmdbClient.popularTv(currentPage)).thenThrow(tvFailure);
            assertThatThrownBy(() -> service.browse("tv", currentPage)).isInstanceOf(CatalogUpstreamException.class);
        }

        // TMDB backs both movies and tv, and ProviderCircuitBreaker is keyed
        // by provider name alone — five tv failures must open the same
        // breaker a movies request would hit.
        assertThatThrownBy(() -> service.browse("movies", 999)).isInstanceOf(CatalogUpstreamException.class);
        verify(tmdbClient, never()).popularMovies(999);
    }

    @Test
    void searchingMoviesRoutesToTmdbSearchMoviesWithTheGivenQuery() {
        var page = new CatalogPageResult(List.of(ITEM), 1, false);
        when(tmdbClient.searchMovies("blade runner", 1)).thenReturn(page);

        var result = service.browse("movies", "blade runner", 1);

        assertThat(result).isEqualTo(page);
        verify(tmdbClient, times(1)).searchMovies("blade runner", 1);
        verify(tmdbClient, never()).popularMovies(anyInt());
    }

    @Test
    void searchingTvRoutesToTmdbSearchTv() {
        var tvItem = new CatalogItem("TMDB", "2", "tv", "A Series", "cover", LocalDate.of(2020, 1, 1), 8.0, 10.0);
        var page = new CatalogPageResult(List.of(tvItem), 1, false);
        when(tmdbClient.searchTv("stranger things", 1)).thenReturn(page);

        var result = service.browse("tv", "stranger things", 1);

        assertThat(result).isEqualTo(page);
        verify(tmdbClient, times(1)).searchTv("stranger things", 1);
        verify(tmdbClient, never()).popularTv(anyInt());
    }

    @Test
    void searchingBooksRoutesToOpenLibrarySearch() {
        var bookItem = new CatalogItem("OpenLibrary", "OL1W", "books", "A Book", "cover", null, 4.2, 5.0);
        var page = new CatalogPageResult(List.of(bookItem), 1, false);
        when(openLibraryClient.search("dune", 1)).thenReturn(page);

        var result = service.browse("books", "dune", 1);

        assertThat(result).isEqualTo(page);
        verify(openLibraryClient, times(1)).search("dune", 1);
        verify(openLibraryClient, never()).trendingBooks(anyInt());
    }

    @Test
    void searchingGamesRoutesToIgdbSearch() {
        var gameItem = new CatalogItem("IGDB", "42", "games", "A Game", "cover", null, 84.0, 100.0);
        var page = new CatalogPageResult(List.of(gameItem), 1, false);
        when(igdbClient.search("witcher", 1)).thenReturn(page);

        var result = service.browse("games", "witcher", 1);

        assertThat(result).isEqualTo(page);
        verify(igdbClient, times(1)).search("witcher", 1);
        verify(igdbClient, never()).popularGames(anyInt());
    }

    @Test
    void aNullSearchFallsThroughToThePopularFeed() {
        var page = new CatalogPageResult(List.of(ITEM), 1, true);
        when(tmdbClient.popularMovies(1)).thenReturn(page);

        var result = service.browse("movies", null, 1);

        assertThat(result).isEqualTo(page);
        verify(tmdbClient, never()).searchMovies(any(), anyInt());
    }

    @Test
    void anEmptySearchFallsThroughToThePopularFeed() {
        var page = new CatalogPageResult(List.of(ITEM), 1, true);
        when(tmdbClient.popularMovies(1)).thenReturn(page);

        var result = service.browse("movies", "", 1);

        assertThat(result).isEqualTo(page);
        verify(tmdbClient, never()).searchMovies(any(), anyInt());
    }

    @Test
    void aWhitespaceOnlySearchFallsThroughToThePopularFeed() {
        var page = new CatalogPageResult(List.of(ITEM), 1, true);
        when(tmdbClient.popularMovies(1)).thenReturn(page);

        var result = service.browse("movies", "   ", 1);

        assertThat(result).isEqualTo(page);
        verify(tmdbClient, never()).searchMovies(any(), anyInt());
    }

    @Test
    void anUnsupportedMediaTypeWithASearchIsRejectedWithoutCallingAnyProvider() {
        assertThatThrownBy(() -> service.browse("podcasts", "anything", 1))
            .isInstanceOf(UnsupportedCatalogMediaTypeException.class);

        verifyNoInteractions(tmdbClient, openLibraryClient, igdbClient);
    }

    @Test
    void aMixedCaseSearchIsLowercasedBeforeReachingTheProvider() {
        var page = new CatalogPageResult(List.of(ITEM), 1, false);
        when(tmdbClient.searchMovies("blade runner", 1)).thenReturn(page);

        var result = service.browse("movies", "Blade Runner", 1);

        assertThat(result).isEqualTo(page);
        verify(tmdbClient, times(1)).searchMovies("blade runner", 1);
    }

    @Test
    void searchesDifferingOnlyByCaseShareOneCacheEntry() {
        var page = new CatalogPageResult(List.of(ITEM), 1, false);
        when(tmdbClient.searchMovies("blade runner", 1)).thenReturn(page);

        assertThat(service.browse("movies", "Blade Runner", 1)).isEqualTo(page);
        assertThat(service.browse("movies", "BLADE RUNNER", 1)).isEqualTo(page);
        assertThat(service.browse("movies", "blade runner", 1)).isEqualTo(page);

        verify(tmdbClient, times(1)).searchMovies("blade runner", 1);
    }

    @Test
    void searchesDifferingOnlyByExtraInternalWhitespaceShareOneCacheEntry() {
        var page = new CatalogPageResult(List.of(ITEM), 1, false);
        when(tmdbClient.searchMovies("blade runner", 1)).thenReturn(page);

        assertThat(service.browse("movies", "blade   runner", 1)).isEqualTo(page);
        assertThat(service.browse("movies", "blade runner", 1)).isEqualTo(page);

        verify(tmdbClient, times(1)).searchMovies("blade runner", 1);
    }

    @Test
    void popularAndSearchPagesForTheSamePageNumberAreCachedIndependently() {
        var popularPage = new CatalogPageResult(List.of(ITEM), 1, true);
        var searchPage = new CatalogPageResult(List.of(ITEM), 1, false);
        when(tmdbClient.popularMovies(1)).thenReturn(popularPage);
        when(tmdbClient.searchMovies("blade runner", 1)).thenReturn(searchPage);

        var popularResult = service.browse("movies", 1);
        var searchResult = service.browse("movies", "blade runner", 1);
        // Re-requesting each must not cost a second upstream call — proves
        // they landed in distinct cache entries rather than one clobbering
        // the other.
        var popularAgain = service.browse("movies", 1);
        var searchAgain = service.browse("movies", "blade runner", 1);

        assertThat(popularResult).isEqualTo(popularPage);
        assertThat(searchResult).isEqualTo(searchPage);
        assertThat(popularAgain).isEqualTo(popularPage);
        assertThat(searchAgain).isEqualTo(searchPage);
        verify(tmdbClient, times(1)).popularMovies(1);
        verify(tmdbClient, times(1)).searchMovies("blade runner", 1);
    }

    @Test
    void searchingTheLiteralWordPopularDoesNotCollideWithThePopularFeedCache() {
        var popularPage = new CatalogPageResult(List.of(ITEM), 1, true);
        var searchItem = new CatalogItem("TMDB", "99", "movies", "Popular (the film)", "cover", LocalDate.of(2024, 1, 1), 6.0, 10.0);
        var searchPage = new CatalogPageResult(List.of(searchItem), 1, false);
        when(tmdbClient.popularMovies(1)).thenReturn(popularPage);
        when(tmdbClient.searchMovies("popular", 1)).thenReturn(searchPage);

        var popularResult = service.browse("movies", 1);
        var searchResult = service.browse("movies", "popular", 1);

        assertThat(popularResult).isEqualTo(popularPage);
        assertThat(searchResult).isEqualTo(searchPage);
    }

    @Test
    void twoDifferentSearchQueriesForTheSameMediaTypeAreCachedIndependently() {
        var firstPage = new CatalogPageResult(List.of(ITEM), 1, false);
        var secondItem = new CatalogItem("TMDB", "5", "movies", "Another Movie", "cover", LocalDate.of(2024, 1, 1), 6.0, 10.0);
        var secondPage = new CatalogPageResult(List.of(secondItem), 1, false);
        when(tmdbClient.searchMovies("blade runner", 1)).thenReturn(firstPage);
        when(tmdbClient.searchMovies("dune", 1)).thenReturn(secondPage);

        assertThat(service.browse("movies", "blade runner", 1)).isEqualTo(firstPage);
        assertThat(service.browse("movies", "dune", 1)).isEqualTo(secondPage);
        // Re-requesting the first query must not cost a second upstream call.
        assertThat(service.browse("movies", "blade runner", 1)).isEqualTo(firstPage);

        verify(tmdbClient, times(1)).searchMovies("blade runner", 1);
        verify(tmdbClient, times(1)).searchMovies("dune", 1);
    }

    @Test
    void aColdSearchMissOnUpstreamFailureThrowsRatherThanFabricatingAResult() {
        when(tmdbClient.searchMovies("blade runner", 1))
            .thenThrow(new CatalogUpstreamException("TMDB", new RuntimeException("boom")));

        assertThatThrownBy(() -> service.browse("movies", "blade runner", 1)).isInstanceOf(CatalogUpstreamException.class);
    }

    @Test
    void aWarmButExpiredSearchPageIsServedStaleOnAnUpstreamFailureJustLikePopular() {
        var page = new CatalogPageResult(List.of(ITEM), 1, false);
        when(tmdbClient.searchMovies("blade runner", 1)).thenReturn(page);
        service.browse("movies", "blade runner", 1);
        ticker.advance(Duration.ofDays(7).plusSeconds(1));
        when(tmdbClient.searchMovies("blade runner", 1))
            .thenThrow(new CatalogUpstreamException("TMDB", new RuntimeException("boom")));

        var result = service.browse("movies", "blade runner", 1);

        assertThat(result).isEqualTo(page);
    }

    @Test
    void fiveSearchFailuresOpenTheSharedTmdbBreakerAndAlsoBlockPopular() {
        var failure = new CatalogUpstreamException("TMDB", new RuntimeException("boom"));
        for (var page = 1; page <= 5; page++) {
            var currentPage = page;
            when(tmdbClient.searchMovies("blade runner", currentPage)).thenThrow(failure);
            assertThatThrownBy(() -> service.browse("movies", "blade runner", currentPage))
                .isInstanceOf(CatalogUpstreamException.class);
        }

        // TMDB's breaker is shared across popular and search (keyed by
        // provider name alone, per ProviderCircuitBreaker) — five search
        // failures must open the same breaker a popular-feed request hits.
        assertThatThrownBy(() -> service.browse("movies", 999)).isInstanceOf(CatalogUpstreamException.class);
        verify(tmdbClient, never()).popularMovies(999);
    }

    @Test
    void sortedMoviesRoutesToTmdbDiscoverMoviesWithTheGivenSortAndDirection() {
        var page = new CatalogPageResult(List.of(ITEM), 1, true);
        when(tmdbClient.discoverMovies("title", "asc", null, null, 1)).thenReturn(page);

        var result = service.browse("movies", null, "title", "asc", NO_FILTERS, 7L, 1);

        assertThat(result).isEqualTo(page);
        verify(tmdbClient, times(1)).discoverMovies("title", "asc", null, null, 1);
        verify(tmdbClient, never()).popularMovies(anyInt());
    }

    @Test
    void sortedTvRoutesToTmdbDiscoverTv() {
        var tvItem = new CatalogItem("TMDB", "2", "tv", "A Series", "cover", LocalDate.of(2020, 1, 1), 8.0, 10.0);
        var page = new CatalogPageResult(List.of(tvItem), 1, true);
        when(tmdbClient.discoverTv("release_date", "desc", null, null, 1)).thenReturn(page);

        var result = service.browse("tv", null, "release_date", "desc", NO_FILTERS, 7L, 1);

        assertThat(result).isEqualTo(page);
        verify(tmdbClient, times(1)).discoverTv("release_date", "desc", null, null, 1);
    }

    @Test
    void sortedBooksRoutesToOpenLibrarySortedBooks() {
        var bookItem = new CatalogItem("OpenLibrary", "OL1W", "books", "A Book", "cover", null, 4.2, 5.0);
        var page = new CatalogPageResult(List.of(bookItem), 1, true);
        when(openLibraryClient.sortedBooks("external_rating", "desc", List.of(), 1)).thenReturn(page);

        var result = service.browse("books", null, "external_rating", "desc", NO_FILTERS, 7L, 1);

        assertThat(result).isEqualTo(page);
        verify(openLibraryClient, times(1)).sortedBooks("external_rating", "desc", List.of(), 1);
        verify(openLibraryClient, never()).trendingBooks(anyInt());
    }

    @Test
    void sortedGamesRoutesToIgdbDiscoverGames() {
        var gameItem = new CatalogItem("IGDB", "42", "games", "A Game", "cover", null, 84.0, 100.0);
        var page = new CatalogPageResult(List.of(gameItem), 1, true);
        when(igdbClient.discoverGames("popularity", "asc", null, null, 1)).thenReturn(page);

        var result = service.browse("games", null, "popularity", "asc", NO_FILTERS, 7L, 1);

        assertThat(result).isEqualTo(page);
        verify(igdbClient, times(1)).discoverGames("popularity", "asc", null, null, 1);
    }

    @Test
    void aSuccessfulSortedFetchPersistsTheChoiceToTheSurfacePreferenceStore() {
        when(tmdbClient.discoverMovies("popularity", "desc", null, null, 1))
            .thenReturn(new CatalogPageResult(List.of(ITEM), 1, true));

        service.browse("movies", null, "popularity", "desc", NO_FILTERS, 42L, 1);

        verify(surfacePreferenceStore, times(1)).upsert(42L, "catalog", "movies", "popularity", "desc", null);
    }

    @Test
    void anUnrecognisedSortKeyFallsBackToThePopularFeedWithoutPersisting() {
        var page = new CatalogPageResult(List.of(ITEM), 1, true);
        when(tmdbClient.popularMovies(1)).thenReturn(page);

        var result = service.browse("movies", null, "release_year", "desc", NO_FILTERS, 42L, 1);

        assertThat(result).isEqualTo(page);
        verify(tmdbClient, never()).discoverMovies(any(), any(), any(), any(), anyInt());
        verify(surfacePreferenceStore, never()).upsert(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void anUnrecognisedDirectionFallsBackToThePopularFeedWithoutPersisting() {
        var page = new CatalogPageResult(List.of(ITEM), 1, true);
        when(tmdbClient.popularMovies(1)).thenReturn(page);

        var result = service.browse("movies", null, "popularity", "sideways", NO_FILTERS, 42L, 1);

        assertThat(result).isEqualTo(page);
        verify(tmdbClient, never()).discoverMovies(any(), any(), any(), any(), anyInt());
        verify(surfacePreferenceStore, never()).upsert(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void aSortKeyWithNoDirectionFallsBackToThePopularFeedWithoutPersisting() {
        var page = new CatalogPageResult(List.of(ITEM), 1, true);
        when(tmdbClient.popularMovies(1)).thenReturn(page);

        var result = service.browse("movies", null, "popularity", null, NO_FILTERS, 42L, 1);

        assertThat(result).isEqualTo(page);
        verify(surfacePreferenceStore, never()).upsert(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void aSortIsIgnoredAndNotPersistedWhileASearchIsActive() {
        var page = new CatalogPageResult(List.of(ITEM), 1, false);
        when(tmdbClient.searchMovies("blade runner", 1)).thenReturn(page);

        var result = service.browse("movies", "blade runner", "popularity", "desc", NO_FILTERS, 42L, 1);

        assertThat(result).isEqualTo(page);
        verify(tmdbClient, times(1)).searchMovies("blade runner", 1);
        verify(tmdbClient, never()).discoverMovies(any(), any(), any(), any(), anyInt());
        verify(surfacePreferenceStore, never()).upsert(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void anUpstreamFailureDuringASortedFetchDoesNotPersistTheUnprovenSort() {
        when(tmdbClient.discoverMovies("popularity", "desc", null, null, 1))
            .thenThrow(new CatalogUpstreamException("TMDB", new RuntimeException("boom")));

        assertThatThrownBy(() -> service.browse("movies", null, "popularity", "desc", NO_FILTERS, 42L, 1))
            .isInstanceOf(CatalogUpstreamException.class);

        verify(surfacePreferenceStore, never()).upsert(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void anUnsupportedMediaTypeWithASortIsRejectedWithoutCallingAnyProviderOrPersisting() {
        assertThatThrownBy(() -> service.browse("podcasts", null, "popularity", "desc", NO_FILTERS, 42L, 1))
            .isInstanceOf(UnsupportedCatalogMediaTypeException.class);

        verifyNoInteractions(tmdbClient, openLibraryClient, igdbClient, surfacePreferenceStore);
    }

    @Test
    void theSortedFeedIsCachedSeparatelyFromThePlainPopularFeedForTheSamePage() {
        var popularPage = new CatalogPageResult(List.of(ITEM), 1, true);
        var sortedItem = new CatalogItem("TMDB", "9", "movies", "Sorted Title", "cover", LocalDate.of(2024, 1, 1), 5.0, 10.0);
        var sortedPage = new CatalogPageResult(List.of(sortedItem), 1, true);
        when(tmdbClient.popularMovies(1)).thenReturn(popularPage);
        when(tmdbClient.discoverMovies("title", "asc", null, null, 1)).thenReturn(sortedPage);

        var popular = service.browse("movies", 1);
        var sorted = service.browse("movies", null, "title", "asc", NO_FILTERS, 42L, 1);
        // Re-requesting each must not cost a second upstream call — proves
        // they landed in distinct cache entries rather than one clobbering
        // the other.
        var popularAgain = service.browse("movies", 1);
        var sortedAgain = service.browse("movies", null, "title", "asc", NO_FILTERS, 42L, 1);

        assertThat(popular).isEqualTo(popularPage);
        assertThat(sorted).isEqualTo(sortedPage);
        assertThat(popularAgain).isEqualTo(popularPage);
        assertThat(sortedAgain).isEqualTo(sortedPage);
        verify(tmdbClient, times(1)).popularMovies(1);
        verify(tmdbClient, times(1)).discoverMovies("title", "asc", null, null, 1);
    }

    @Test
    void twoDifferentSortDirectionsForTheSameKeyAreCachedIndependently() {
        var ascItem = new CatalogItem("TMDB", "1", "movies", "A Title", "cover", LocalDate.of(2024, 1, 1), 5.0, 10.0);
        var descItem = new CatalogItem("TMDB", "2", "movies", "Z Title", "cover", LocalDate.of(2024, 1, 1), 5.0, 10.0);
        when(tmdbClient.discoverMovies("title", "asc", null, null, 1)).thenReturn(new CatalogPageResult(List.of(ascItem), 1, true));
        when(tmdbClient.discoverMovies("title", "desc", null, null, 1)).thenReturn(new CatalogPageResult(List.of(descItem), 1, true));

        var asc = service.browse("movies", null, "title", "asc", NO_FILTERS, 42L, 1);
        var desc = service.browse("movies", null, "title", "desc", NO_FILTERS, 42L, 1);

        assertThat(asc.items()).containsExactly(ascItem);
        assertThat(desc.items()).containsExactly(descItem);
        verify(tmdbClient, times(1)).discoverMovies("title", "asc", null, null, 1);
        verify(tmdbClient, times(1)).discoverMovies("title", "desc", null, null, 1);
    }

    @Test
    void genreFilteredMoviesRoutesToTmdbDiscoverMoviesWithTheGivenGenre() {
        stubGenre("movies", "28", "Action");
        var actionItem = new CatalogItem("TMDB", "77", "movies", "An Action Movie", "cover", LocalDate.of(2024, 1, 1), 7.0, 10.0);
        var page = new CatalogPageResult(List.of(actionItem), 1, true);
        when(tmdbClient.discoverMovies("popularity", "desc", "28", null, 1)).thenReturn(page);

        var result = service.browse("movies", null, "popularity", "desc", genreFilter("28"), 42L, 1);

        assertThat(result).isEqualTo(page);
        verify(tmdbClient, times(1)).discoverMovies("popularity", "desc", "28", null, 1);
        verify(tmdbClient, never()).popularMovies(anyInt());
        verify(surfacePreferenceStore).upsert(42L, "catalog", "movies", "popularity", "desc", encodedGenre("28"));
    }

    @Test
    void genreFilteredTvRoutesToTmdbDiscoverTvWithTheGivenGenre() {
        stubGenre("tv", "10759", "Action & Adventure");
        var tvItem = new CatalogItem("TMDB", "2", "tv", "A Series", "cover", LocalDate.of(2020, 1, 1), 8.0, 10.0);
        var page = new CatalogPageResult(List.of(tvItem), 1, true);
        when(tmdbClient.discoverTv("popularity", "desc", "10759", null, 1)).thenReturn(page);

        var result = service.browse("tv", null, "popularity", "desc", genreFilter("10759"), 42L, 1);

        assertThat(result).isEqualTo(page);
        verify(tmdbClient, times(1)).discoverTv("popularity", "desc", "10759", null, 1);
    }

    @Test
    void genreFilteredGamesRoutesToIgdbDiscoverGamesWithTheGivenGenre() {
        stubGenre("games", "5", "Shooter");
        var gameItem = new CatalogItem("IGDB", "42", "games", "A Game", "cover", null, 84.0, 100.0);
        var page = new CatalogPageResult(List.of(gameItem), 1, true);
        when(igdbClient.discoverGames("popularity", "desc", "5", null, 1)).thenReturn(page);

        var result = service.browse("games", null, "popularity", "desc", genreFilter("5"), 42L, 1);

        assertThat(result).isEqualTo(page);
        verify(igdbClient, times(1)).discoverGames("popularity", "desc", "5", null, 1);
    }

    @Test
    void availableInLanguageFilteredGamesRoutesToIgdbDiscoverGamesWithTheGivenLanguage() {
        stubAvailableInLanguage("games", "2");
        var germanGame = new CatalogItem("IGDB", "77", "games", "A German-Supported Game", "cover", null, 80.0, 100.0);
        var page = new CatalogPageResult(List.of(germanGame), 1, true);
        when(igdbClient.discoverGames("popularity", "desc", null, "2", 1)).thenReturn(page);

        var result = service.browse("games", null, "popularity", "desc", availableInLanguageFilter("2"), 42L, 1);

        assertThat(result).isEqualTo(page);
        verify(igdbClient, times(1)).discoverGames("popularity", "desc", null, "2", 1);
        verify(igdbClient, never()).popularGames(anyInt());
        verify(surfacePreferenceStore).upsert(42L, "catalog", "games", "popularity", "desc", encodedFilters("availableInLanguage", "2"));
    }

    @Test
    void availableInLanguageIsNotOfferedOrAppliedForMoviesTvOrBooks() {
        when(languageVocabulary.supportsAvailableInLanguage("movies")).thenReturn(false);
        var page = new CatalogPageResult(List.of(ITEM), 1, true);
        when(tmdbClient.popularMovies(1)).thenReturn(page);

        var result = service.browse("movies", null, null, null, availableInLanguageFilter("2"), 42L, 1);

        assertThat(result).isEqualTo(page);
        verify(tmdbClient, never()).discoverMovies(any(), any(), any(), any(), anyInt());
        verify(surfacePreferenceStore, never()).upsert(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void anUnrecognisedAvailableInLanguageValueIsDroppedRatherThanPassedThrough() {
        stubAvailableInLanguage("games", "2");
        var page = new CatalogPageResult(List.of(), 1, true);
        when(igdbClient.popularGames(1)).thenReturn(page);

        var result = service.browse("games", null, null, null, availableInLanguageFilter("not-a-real-language-id"), 42L, 1);

        assertThat(result).isEqualTo(page);
        verify(igdbClient, never()).discoverGames(any(), any(), any(), any(), anyInt());
        verify(surfacePreferenceStore, never()).upsert(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void combiningGenreAndAvailableInLanguageNarrowsGamesToTheIntersection() {
        stubGenre("games", "5", "Shooter");
        stubAvailableInLanguage("games", "2");
        var page = new CatalogPageResult(List.of(), 1, true);
        when(igdbClient.discoverGames("popularity", "desc", "5", "2", 1)).thenReturn(page);
        var combined = new LinkedHashMap<>(genreFilter("5"));
        combined.putAll(availableInLanguageFilter("2"));

        var result = service.browse("games", null, "popularity", "desc", combined, 42L, 1);

        assertThat(result).isEqualTo(page);
        verify(igdbClient, times(1)).discoverGames("popularity", "desc", "5", "2", 1);
    }

    @Test
    void settingAvailableInLanguagePreservesAPreviouslyPersistedGenreForGamesRatherThanClobberingIt() {
        stubGenre("games", "5", "Shooter");
        stubAvailableInLanguage("games", "2");
        var existing = new SurfacePreference(
            42L, "catalog", "games", "popularity", "desc", encodedFilters("genre", "5"), clock.instant()
        );
        when(surfacePreferenceStore.get(42L, "catalog", "games")).thenReturn(Optional.of(existing));
        when(igdbClient.discoverGames("popularity", "desc", "5", "2", 1))
            .thenReturn(new CatalogPageResult(List.of(), 1, true));

        service.browse("games", null, "popularity", "desc", availableInLanguageFilter("2"), 42L, 1);

        verify(igdbClient, times(1)).discoverGames("popularity", "desc", "5", "2", 1);
        verify(surfacePreferenceStore)
            .upsert(42L, "catalog", "games", "popularity", "desc", encodedFilters("genre", "5", "availableInLanguage", "2"));
    }

    @Test
    void genreFilteredBooksRoutesToOpenLibrarySortedBooksWithTheResolvedSubjectAliases() {
        stubGenre("books", "science_fiction", "Science Fiction");
        var aliases = List.of("Science fiction", "Sci-Fi", "Science-fiction", "Speculative fiction");
        when(genreVocabulary.booksSubjectAliases("science_fiction")).thenReturn(aliases);
        var bookItem = new CatalogItem("OpenLibrary", "OL1W", "books", "A Sci-Fi Book", "cover", null, 4.2, 5.0);
        var page = new CatalogPageResult(List.of(bookItem), 1, true);
        when(openLibraryClient.sortedBooks("popularity", "desc", aliases, 1)).thenReturn(page);

        var result = service.browse("books", null, null, null, genreFilter("science_fiction"), 42L, 1);

        assertThat(result).isEqualTo(page);
        verify(openLibraryClient, times(1)).sortedBooks("popularity", "desc", aliases, 1);
        verify(openLibraryClient, never()).trendingBooks(anyInt());
        verify(surfacePreferenceStore).upsert(42L, "catalog", "books", null, null, encodedGenre("science_fiction"));
    }

    @Test
    void anInvalidBooksGenreValueIsDroppedRatherThanPassedThrough() {
        stubGenre("books", "science_fiction", "Science Fiction");
        var bookItem = new CatalogItem("OpenLibrary", "OL1W", "books", "A Book", "cover", null, 4.2, 5.0);
        var page = new CatalogPageResult(List.of(bookItem), 1, true);
        when(openLibraryClient.trendingBooks(1)).thenReturn(page);

        var result = service.browse("books", null, null, null, genreFilter("not-a-curated-genre"), 42L, 1);

        assertThat(result).isEqualTo(page);
        verify(openLibraryClient, never()).sortedBooks(any(), any(), any(), anyInt());
        verify(surfacePreferenceStore, never()).upsert(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void anUnrecognisedGenreValueIsDroppedRatherThanPassedThrough() {
        stubGenre("movies", "28", "Action");
        var page = new CatalogPageResult(List.of(ITEM), 1, true);
        when(tmdbClient.popularMovies(1)).thenReturn(page);

        var result = service.browse("movies", null, null, null, genreFilter("not-a-real-genre-id"), 42L, 1);

        assertThat(result).isEqualTo(page);
        verify(tmdbClient, never()).discoverMovies(any(), any(), any(), any(), anyInt());
        verify(surfacePreferenceStore, never()).upsert(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void selectingADifferentGenreReplacesRatherThanCombiningWithThePrevious() {
        when(genreVocabulary.supports("movies")).thenReturn(true);
        when(genreVocabulary.genresFor("movies"))
            .thenReturn(List.of(new CatalogFilterOption("28", "Action"), new CatalogFilterOption("35", "Comedy")));
        var actionItem = new CatalogItem("TMDB", "1", "movies", "Action Movie", "cover", LocalDate.of(2024, 1, 1), 7.0, 10.0);
        var comedyItem = new CatalogItem("TMDB", "2", "movies", "Comedy Movie", "cover", LocalDate.of(2024, 1, 1), 6.0, 10.0);
        when(tmdbClient.discoverMovies("popularity", "desc", "28", null, 1))
            .thenReturn(new CatalogPageResult(List.of(actionItem), 1, true));
        when(tmdbClient.discoverMovies("popularity", "desc", "35", null, 1))
            .thenReturn(new CatalogPageResult(List.of(comedyItem), 1, true));

        var first = service.browse("movies", null, "popularity", "desc", genreFilter("28"), 42L, 1);
        var second = service.browse("movies", null, "popularity", "desc", genreFilter("35"), 42L, 1);

        assertThat(first.items()).containsExactly(actionItem);
        assertThat(second.items()).containsExactly(comedyItem);
        verify(tmdbClient, times(1)).discoverMovies("popularity", "desc", "28", null, 1);
        verify(tmdbClient, times(1)).discoverMovies("popularity", "desc", "35", null, 1);
        verify(surfacePreferenceStore).upsert(42L, "catalog", "movies", "popularity", "desc", encodedGenre("28"));
        verify(surfacePreferenceStore).upsert(42L, "catalog", "movies", "popularity", "desc", encodedGenre("35"));
    }

    @Test
    void aGenreFilteredFeedIsCachedSeparatelyFromThePlainSortedFeedForTheSamePage() {
        stubGenre("movies", "28", "Action");
        var sortedOnlyPage = new CatalogPageResult(List.of(ITEM), 1, true);
        var genreItem = new CatalogItem("TMDB", "77", "movies", "Action Movie", "cover", LocalDate.of(2024, 1, 1), 7.0, 10.0);
        var genreFilteredPage = new CatalogPageResult(List.of(genreItem), 1, true);
        when(tmdbClient.discoverMovies("popularity", "desc", null, null, 1)).thenReturn(sortedOnlyPage);
        when(tmdbClient.discoverMovies("popularity", "desc", "28", null, 1)).thenReturn(genreFilteredPage);

        var sortedOnly = service.browse("movies", null, "popularity", "desc", NO_FILTERS, 42L, 1);
        var genreFiltered = service.browse("movies", null, "popularity", "desc", genreFilter("28"), 42L, 1);
        var sortedOnlyAgain = service.browse("movies", null, "popularity", "desc", NO_FILTERS, 42L, 1);
        var genreFilteredAgain = service.browse("movies", null, "popularity", "desc", genreFilter("28"), 42L, 1);

        assertThat(sortedOnly).isEqualTo(sortedOnlyPage);
        assertThat(genreFiltered).isEqualTo(genreFilteredPage);
        assertThat(sortedOnlyAgain).isEqualTo(sortedOnlyPage);
        assertThat(genreFilteredAgain).isEqualTo(genreFilteredPage);
        verify(tmdbClient, times(1)).discoverMovies("popularity", "desc", null, null, 1);
        verify(tmdbClient, times(1)).discoverMovies("popularity", "desc", "28", null, 1);
    }

    @Test
    void changingOnlyTheSortPreservesAPreviouslyPersistedGenreRatherThanClobberingIt() {
        stubGenre("movies", "28", "Action");
        var existing = new SurfacePreference(42L, "catalog", "movies", "title", "asc", encodedGenre("28"), clock.instant());
        when(surfacePreferenceStore.get(42L, "catalog", "movies")).thenReturn(Optional.of(existing));
        when(tmdbClient.discoverMovies("popularity", "desc", "28", null, 1))
            .thenReturn(new CatalogPageResult(List.of(ITEM), 1, true));

        service.browse("movies", null, "popularity", "desc", NO_FILTERS, 42L, 1);

        verify(tmdbClient, times(1)).discoverMovies("popularity", "desc", "28", null, 1);
        verify(surfacePreferenceStore).upsert(42L, "catalog", "movies", "popularity", "desc", encodedGenre("28"));
    }

    @Test
    void changingOnlyTheGenrePreservesAPreviouslyPersistedSortRatherThanClobberingIt() {
        stubGenre("movies", "35", "Comedy");
        var existing = new SurfacePreference(42L, "catalog", "movies", "title", "asc", null, clock.instant());
        when(surfacePreferenceStore.get(42L, "catalog", "movies")).thenReturn(Optional.of(existing));
        when(tmdbClient.discoverMovies("title", "asc", "35", null, 1))
            .thenReturn(new CatalogPageResult(List.of(ITEM), 1, true));

        service.browse("movies", null, null, null, genreFilter("35"), 42L, 1);

        verify(tmdbClient, times(1)).discoverMovies("title", "asc", "35", null, 1);
        verify(surfacePreferenceStore).upsert(42L, "catalog", "movies", "title", "asc", encodedGenre("35"));
    }

    @Test
    void aGenreOnlySelectionWithNoPriorSortAppliesTheDefaultSortForFetchingButPersistsNoSortChoiceOfItsOwn() {
        stubGenre("movies", "28", "Action");
        when(tmdbClient.discoverMovies("popularity", "desc", "28", null, 1))
            .thenReturn(new CatalogPageResult(List.of(ITEM), 1, true));

        service.browse("movies", null, null, null, genreFilter("28"), 42L, 1);

        verify(tmdbClient, times(1)).discoverMovies("popularity", "desc", "28", null, 1);
        verify(surfacePreferenceStore).upsert(42L, "catalog", "movies", null, null, encodedGenre("28"));
    }

    // An empty-string genre value is CatalogController's distinct "the
    // frontend sent a present-but-empty genre=" signal for a deselected
    // filter chip — not the same wire value as the field being absent from
    // the filters map entirely (NO_FILTERS), which instead falls back to
    // whatever's persisted (the tests above). Collapsing the two would mean
    // a deselected chip can never actually clear a previously-chosen genre —
    // see the browse() Javadoc.
    @Test
    void anExplicitlyEmptyGenreClearsAPreviouslyPersistedGenreRatherThanFallingBackToIt() {
        var existing = new SurfacePreference(42L, "catalog", "movies", "title", "asc", encodedGenre("28"), clock.instant());
        when(surfacePreferenceStore.get(42L, "catalog", "movies")).thenReturn(Optional.of(existing));
        when(tmdbClient.discoverMovies("title", "asc", null, null, 1))
            .thenReturn(new CatalogPageResult(List.of(ITEM), 1, true));

        var result = service.browse("movies", null, "title", "asc", genreFilter(""), 42L, 1);

        assertThat(result.items()).containsExactly(ITEM);
        verify(tmdbClient, times(1)).discoverMovies("title", "asc", null, null, 1);
        verify(tmdbClient, never()).discoverMovies(any(), any(), eq("28"), any(), anyInt());
        verify(surfacePreferenceStore).upsert(42L, "catalog", "movies", "title", "asc", null);
    }

    @Test
    void anExplicitlyEmptyGenreWithNoSortGivenStillClearsRatherThanFallingBackToPopularWithoutPersisting() {
        var existing = new SurfacePreference(42L, "catalog", "movies", "title", "asc", encodedGenre("28"), clock.instant());
        when(surfacePreferenceStore.get(42L, "catalog", "movies")).thenReturn(Optional.of(existing));
        when(tmdbClient.discoverMovies("title", "asc", null, null, 1))
            .thenReturn(new CatalogPageResult(List.of(ITEM), 1, true));

        service.browse("movies", null, null, null, genreFilter(""), 42L, 1);

        // The persisted sort ("title"/"asc") still governs the fetch even
        // though this request didn't repeat it — only genre changed.
        verify(tmdbClient, times(1)).discoverMovies("title", "asc", null, null, 1);
        verify(surfacePreferenceStore).upsert(42L, "catalog", "movies", "title", "asc", null);
    }

    @Test
    void originalLanguageFilteredMoviesRoutesToTmdbDiscoverMoviesWithTheGivenLanguage() {
        stubOriginalLanguage("movies", "ja");
        var japaneseItem = new CatalogItem("TMDB", "88", "movies", "A Japanese Film", "cover", LocalDate.of(2024, 1, 1), 7.0, 10.0);
        var page = new CatalogPageResult(List.of(japaneseItem), 1, true);
        when(tmdbClient.discoverMovies("popularity", "desc", null, "ja", 1)).thenReturn(page);

        var result = service.browse("movies", null, "popularity", "desc", originalLanguageFilter("ja"), 42L, 1);

        assertThat(result).isEqualTo(page);
        verify(tmdbClient, times(1)).discoverMovies("popularity", "desc", null, "ja", 1);
        verify(tmdbClient, never()).popularMovies(anyInt());
        verify(surfacePreferenceStore).upsert(42L, "catalog", "movies", "popularity", "desc", encodedFilters("originalLanguage", "ja"));
    }

    @Test
    void originalLanguageFilteredTvRoutesToTmdbDiscoverTvWithTheGivenLanguage() {
        stubOriginalLanguage("tv", "ko");
        var tvItem = new CatalogItem("TMDB", "2", "tv", "A Series", "cover", LocalDate.of(2020, 1, 1), 8.0, 10.0);
        var page = new CatalogPageResult(List.of(tvItem), 1, true);
        when(tmdbClient.discoverTv("popularity", "desc", null, "ko", 1)).thenReturn(page);

        var result = service.browse("tv", null, "popularity", "desc", originalLanguageFilter("ko"), 42L, 1);

        assertThat(result).isEqualTo(page);
        verify(tmdbClient, times(1)).discoverTv("popularity", "desc", null, "ko", 1);
    }

    @Test
    void originalLanguageIsNotOfferedOrAppliedForBooksOrGames() {
        when(languageVocabulary.supportsOriginalLanguage("books")).thenReturn(false);

        var page = new CatalogPageResult(List.of(), 1, true);
        when(openLibraryClient.trendingBooks(1)).thenReturn(page);

        var result = service.browse("books", null, null, null, originalLanguageFilter("ja"), 42L, 1);

        assertThat(result).isEqualTo(page);
        verify(openLibraryClient, never()).sortedBooks(any(), any(), any(), anyInt());
        verify(surfacePreferenceStore, never()).upsert(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void anUnrecognisedOriginalLanguageValueIsDroppedRatherThanPassedThrough() {
        stubOriginalLanguage("movies", "ja");
        var page = new CatalogPageResult(List.of(ITEM), 1, true);
        when(tmdbClient.popularMovies(1)).thenReturn(page);

        var result = service.browse("movies", null, null, null, originalLanguageFilter("not-a-real-code"), 42L, 1);

        assertThat(result).isEqualTo(page);
        verify(tmdbClient, never()).discoverMovies(any(), any(), any(), any(), anyInt());
        verify(surfacePreferenceStore, never()).upsert(anyLong(), any(), any(), any(), any(), any());
    }

    // Two DIFFERENT filter kinds combine (narrow to the intersection) even
    // though each kind alone is single-select — this is not in tension with
    // "selecting a different genre replaces rather than combines with the
    // previous one" above, which is about two values of the SAME kind.
    @Test
    void combiningGenreAndOriginalLanguageNarrowsToBothAtOnce() {
        stubGenre("movies", "28", "Action");
        stubOriginalLanguage("movies", "ja");
        var page = new CatalogPageResult(List.of(ITEM), 1, true);
        when(tmdbClient.discoverMovies("popularity", "desc", "28", "ja", 1)).thenReturn(page);
        var combined = new LinkedHashMap<>(genreFilter("28"));
        combined.putAll(originalLanguageFilter("ja"));

        var result = service.browse("movies", null, "popularity", "desc", combined, 42L, 1);

        assertThat(result).isEqualTo(page);
        verify(tmdbClient, times(1)).discoverMovies("popularity", "desc", "28", "ja", 1);
        verify(surfacePreferenceStore).upsert(42L, "catalog", "movies", "popularity", "desc", encodedFilters("genre", "28", "originalLanguage", "ja"));
    }

    @Test
    void settingOriginalLanguagePreservesAPreviouslyPersistedGenreRatherThanClobberingIt() {
        stubGenre("movies", "28", "Action");
        stubOriginalLanguage("movies", "ja");
        var existing = new SurfacePreference(
            42L, "catalog", "movies", "popularity", "desc", encodedFilters("genre", "28"), clock.instant()
        );
        when(surfacePreferenceStore.get(42L, "catalog", "movies")).thenReturn(Optional.of(existing));
        when(tmdbClient.discoverMovies("popularity", "desc", "28", "ja", 1))
            .thenReturn(new CatalogPageResult(List.of(ITEM), 1, true));

        service.browse("movies", null, "popularity", "desc", originalLanguageFilter("ja"), 42L, 1);

        verify(tmdbClient, times(1)).discoverMovies("popularity", "desc", "28", "ja", 1);
        verify(surfacePreferenceStore)
            .upsert(42L, "catalog", "movies", "popularity", "desc", encodedFilters("genre", "28", "originalLanguage", "ja"));
    }

    @Test
    void settingGenrePreservesAPreviouslyPersistedOriginalLanguageRatherThanClobberingIt() {
        stubGenre("movies", "28", "Action");
        stubOriginalLanguage("movies", "ja");
        var existing = new SurfacePreference(
            42L, "catalog", "movies", "popularity", "desc", encodedFilters("originalLanguage", "ja"), clock.instant()
        );
        when(surfacePreferenceStore.get(42L, "catalog", "movies")).thenReturn(Optional.of(existing));
        when(tmdbClient.discoverMovies("popularity", "desc", "28", "ja", 1))
            .thenReturn(new CatalogPageResult(List.of(ITEM), 1, true));

        service.browse("movies", null, "popularity", "desc", genreFilter("28"), 42L, 1);

        verify(tmdbClient, times(1)).discoverMovies("popularity", "desc", "28", "ja", 1);
        verify(surfacePreferenceStore)
            .upsert(42L, "catalog", "movies", "popularity", "desc", encodedFilters("genre", "28", "originalLanguage", "ja"));
    }

    @Test
    void clearingOnlyOriginalLanguagePreservesThePersistedGenre() {
        stubGenre("movies", "28", "Action");
        var existing = new SurfacePreference(
            42L, "catalog", "movies", "popularity", "desc", encodedFilters("genre", "28", "originalLanguage", "ja"), clock.instant()
        );
        when(surfacePreferenceStore.get(42L, "catalog", "movies")).thenReturn(Optional.of(existing));
        when(tmdbClient.discoverMovies("popularity", "desc", "28", null, 1))
            .thenReturn(new CatalogPageResult(List.of(ITEM), 1, true));

        service.browse("movies", null, "popularity", "desc", originalLanguageFilter(""), 42L, 1);

        verify(tmdbClient, times(1)).discoverMovies("popularity", "desc", "28", null, 1);
        verify(surfacePreferenceStore).upsert(42L, "catalog", "movies", "popularity", "desc", encodedFilters("genre", "28"));
    }

    @Test
    void availableFiltersReportsBothGenreAndOriginalLanguageForMovies() {
        var genreOptions = List.of(new CatalogFilterOption("28", "Action"));
        var languageOptions = List.of(new CatalogFilterOption("ja", "Japanese"));
        when(genreVocabulary.supports("movies")).thenReturn(true);
        when(genreVocabulary.genresFor("movies")).thenReturn(genreOptions);
        when(languageVocabulary.supportsOriginalLanguage("movies")).thenReturn(true);
        when(languageVocabulary.originalLanguageOptionsFor("movies")).thenReturn(languageOptions);

        assertThat(service.availableFilters("movies")).isEqualTo(Map.of("genre", genreOptions, "originalLanguage", languageOptions));
    }

    @Test
    void availableFiltersOmitsOriginalLanguageForBooksAndGames() {
        when(genreVocabulary.supports("books")).thenReturn(true);
        when(genreVocabulary.genresFor("books")).thenReturn(List.of());
        when(languageVocabulary.supportsOriginalLanguage("books")).thenReturn(false);

        assertThat(service.availableFilters("books")).doesNotContainKey("originalLanguage");
    }

    @Test
    void availableFiltersReportsAvailableInLanguageForGames() {
        var options = List.of(new CatalogFilterOption("1", "English"));
        when(genreVocabulary.supports("games")).thenReturn(true);
        when(genreVocabulary.genresFor("games")).thenReturn(List.of());
        when(languageVocabulary.supportsAvailableInLanguage("games")).thenReturn(true);
        when(languageVocabulary.availableInLanguageOptionsFor("games")).thenReturn(options);

        assertThat(service.availableFilters("games")).containsEntry("availableInLanguage", options);
    }

    @Test
    void availableFiltersOmitsAvailableInLanguageForMoviesTvAndBooks() {
        when(languageVocabulary.supportsAvailableInLanguage("movies")).thenReturn(false);
        when(languageVocabulary.supportsAvailableInLanguage("tv")).thenReturn(false);
        when(languageVocabulary.supportsAvailableInLanguage("books")).thenReturn(false);

        assertThat(service.availableFilters("movies")).doesNotContainKey("availableInLanguage");
        assertThat(service.availableFilters("tv")).doesNotContainKey("availableInLanguage");
        assertThat(service.availableFilters("books")).doesNotContainKey("availableInLanguage");
    }

    @Test
    void availableFiltersDegradesToOmittingJustTheUnavailableFieldWhenOneVocabularyFails() {
        var genreOptions = List.of(new CatalogFilterOption("28", "Action"));
        when(genreVocabulary.supports("movies")).thenReturn(true);
        when(genreVocabulary.genresFor("movies")).thenReturn(genreOptions);
        when(languageVocabulary.supportsOriginalLanguage("movies")).thenReturn(true);
        when(languageVocabulary.originalLanguageOptionsFor("movies"))
            .thenThrow(new CatalogUpstreamException("TMDB", new RuntimeException("boom")));

        assertThat(service.availableFilters("movies")).isEqualTo(Map.of("genre", genreOptions));
    }

    @Test
    void availableFiltersReportsGenreForMovies() {
        var options = List.of(new CatalogFilterOption("28", "Action"));
        when(genreVocabulary.supports("movies")).thenReturn(true);
        when(genreVocabulary.genresFor("movies")).thenReturn(options);

        assertThat(service.availableFilters("movies")).isEqualTo(Map.of("genre", options));
    }

    @Test
    void availableFiltersReportsGenreForTv() {
        var options = List.of(new CatalogFilterOption("10759", "Action & Adventure"));
        when(genreVocabulary.supports("tv")).thenReturn(true);
        when(genreVocabulary.genresFor("tv")).thenReturn(options);

        assertThat(service.availableFilters("tv")).isEqualTo(Map.of("genre", options));
    }

    @Test
    void availableFiltersReportsGenreForGames() {
        var options = List.of(new CatalogFilterOption("5", "Shooter"));
        when(genreVocabulary.supports("games")).thenReturn(true);
        when(genreVocabulary.genresFor("games")).thenReturn(options);

        assertThat(service.availableFilters("games")).isEqualTo(Map.of("genre", options));
    }

    @Test
    void availableFiltersReportsTheCuratedGenreListForBooks() {
        var options = List.of(new CatalogFilterOption("science_fiction", "Science Fiction"));
        when(genreVocabulary.supports("books")).thenReturn(true);
        when(genreVocabulary.genresFor("books")).thenReturn(options);

        assertThat(service.availableFilters("books")).isEqualTo(Map.of("genre", options));
    }

    @Test
    void availableFiltersDegradesToEmptyRatherThanFailingWhenTheGenreVocabularyIsTemporarilyUnavailable() {
        when(genreVocabulary.supports("movies")).thenReturn(true);
        when(genreVocabulary.genresFor("movies"))
            .thenThrow(new CatalogUpstreamException("TMDB", new RuntimeException("boom")));

        assertThat(service.availableFilters("movies")).isEmpty();
    }

    @Test
    void preferenceReturnsTheStoredSortAndFilters() {
        stubGenre("movies", "28", "Action");
        var stored = new SurfacePreference(42L, "catalog", "movies", "title", "asc", encodedGenre("28"), clock.instant());
        when(surfacePreferenceStore.get(42L, "catalog", "movies")).thenReturn(Optional.of(stored));

        var result = service.preference(42L, "movies");

        assertThat(result).isEqualTo(new CatalogPreference("title", "asc", Map.of("genre", "28")));
    }

    @Test
    void preferenceReturnsNullSortAndEmptyFiltersWhenTheUserHasNeverSetOne() {
        when(surfacePreferenceStore.get(42L, "catalog", "books")).thenReturn(Optional.empty());

        assertThat(service.preference(42L, "books")).isEqualTo(new CatalogPreference(null, null, Map.of()));
    }

    @Test
    void preferenceReturnsEmptyFiltersWhenTheStoredFiltersBlobIsNull() {
        var stored = new SurfacePreference(42L, "catalog", "movies", "title", "asc", null, clock.instant());
        when(surfacePreferenceStore.get(42L, "catalog", "movies")).thenReturn(Optional.of(stored));

        assertThat(service.preference(42L, "movies").filters()).isEmpty();
        assertThat(service.preference(42L, "movies").sortKey()).isEqualTo("title");
    }

    @Test
    void preferenceReturnsEmptyFiltersWhenTheStoredFiltersBlobIsMalformedJsonRatherThanThrowing() {
        var stored = new SurfacePreference(42L, "catalog", "movies", "title", "asc", "not valid json", clock.instant());
        when(surfacePreferenceStore.get(42L, "catalog", "movies")).thenReturn(Optional.of(stored));

        assertThat(service.preference(42L, "movies").filters()).isEmpty();
    }

    @Test
    void preferenceDropsAStalePersistedGenreThatNoLongerExistsInTheCurrentVocabulary() {
        when(genreVocabulary.supports("movies")).thenReturn(true);
        when(genreVocabulary.genresFor("movies")).thenReturn(List.of(new CatalogFilterOption("35", "Comedy")));
        var stored = new SurfacePreference(42L, "catalog", "movies", "title", "asc", encodedGenre("28"), clock.instant());
        when(surfacePreferenceStore.get(42L, "catalog", "movies")).thenReturn(Optional.of(stored));

        var result = service.preference(42L, "movies");

        assertThat(result.filters()).doesNotContainKey("genre");
        assertThat(result.sortKey()).isEqualTo("title");
    }

    private void stubGenre(String mediaType, String value, String label) {
        when(genreVocabulary.supports(mediaType)).thenReturn(true);
        when(genreVocabulary.genresFor(mediaType)).thenReturn(List.of(new CatalogFilterOption(value, label)));
    }

    private void stubOriginalLanguage(String mediaType, String value) {
        when(languageVocabulary.supportsOriginalLanguage(mediaType)).thenReturn(true);
        when(languageVocabulary.originalLanguageOptionsFor(mediaType)).thenReturn(List.of(new CatalogFilterOption(value, value)));
    }

    private void stubAvailableInLanguage(String mediaType, String value) {
        when(languageVocabulary.supportsAvailableInLanguage(mediaType)).thenReturn(true);
        when(languageVocabulary.availableInLanguageOptionsFor(mediaType)).thenReturn(List.of(new CatalogFilterOption(value, value)));
    }

    private static Map<String, String> genreFilter(String genre) {
        return Map.of(CatalogFilterKeys.GENRE, genre);
    }

    private static Map<String, String> originalLanguageFilter(String originalLanguage) {
        return Map.of(CatalogFilterKeys.ORIGINAL_LANGUAGE, originalLanguage);
    }

    private static Map<String, String> availableInLanguageFilter(String availableInLanguage) {
        return Map.of(CatalogFilterKeys.AVAILABLE_IN_LANGUAGE, availableInLanguage);
    }

    private String encodedGenre(String genre) {
        return objectMapper.writeValueAsString(Map.of("genre", genre));
    }

    // Builds and serialises filters as a LinkedHashMap in the exact order
    // given, not Map.of() — Map.of()'s iteration order is unspecified (and
    // salted per JVM run), so encoding it to JSON for an exact-string
    // Mockito match against CatalogService's own LinkedHashMap-backed
    // encoding would be flaky rather than reliably matching key for key.
    private String encodedFilters(String... keyValuePairs) {
        var filters = new LinkedHashMap<String, String>();
        for (var i = 0; i < keyValuePairs.length; i += 2) {
            filters.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return objectMapper.writeValueAsString(filters);
    }

    private void failFivePages() {
        failFivePages(1);
    }

    private void failFivePages(int startingPage) {
        var failure = new CatalogUpstreamException("TMDB", new RuntimeException("boom"));
        for (var page = startingPage; page < startingPage + 5; page++) {
            var currentPage = page;
            when(tmdbClient.popularMovies(currentPage)).thenThrow(failure);
            assertThatThrownBy(() -> service.browse("movies", currentPage)).isInstanceOf(CatalogUpstreamException.class);
        }
    }
}
