package dev.yzlaboratory.alexandrea.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

    @Mock
    private TmdbClient tmdbClient;

    private CatalogService service;

    @BeforeEach
    void setUp() {
        var cache = new CatalogCache(new MutableTicker());
        service = new CatalogService(tmdbClient, cache);
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
}
