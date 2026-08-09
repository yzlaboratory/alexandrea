package dev.yzlaboratory.alexandrea.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.yzlaboratory.alexandrea.auth.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    private static final CatalogItem ITEM =
        new CatalogItem("TMDB", "1", "movies", "Title", "cover", LocalDate.of(2024, 1, 1), 7.5, 10.0);
    private static final Duration OPEN_WINDOW = Duration.ofSeconds(60);

    @Mock
    private TmdbClient tmdbClient;

    private MutableTicker ticker;
    private MutableClock clock;
    private CatalogService service;

    @BeforeEach
    void setUp() {
        ticker = new MutableTicker();
        clock = new MutableClock(Instant.parse("2026-06-07T12:00:00Z"));
        var cache = new CatalogCache(ticker);
        var circuitBreaker = new ProviderCircuitBreaker(clock);
        service = new CatalogService(tmdbClient, cache, circuitBreaker);
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
        assertThatThrownBy(() -> service.browse("games", 1))
            .isInstanceOf(UnsupportedCatalogMediaTypeException.class);

        verifyNoInteractions(tmdbClient);
    }

    @Test
    void anUnrecognisedMediaTypeIsRejectedTheSameWayAsARecognisedButUnbuiltOne() {
        assertThatThrownBy(() -> service.browse("podcasts", 1))
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
