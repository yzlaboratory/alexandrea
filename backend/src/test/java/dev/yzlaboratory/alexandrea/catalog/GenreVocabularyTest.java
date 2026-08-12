package dev.yzlaboratory.alexandrea.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GenreVocabularyTest {

    @Mock
    private TmdbClient tmdbClient;

    @Mock
    private IgdbClient igdbClient;

    private GenreVocabulary vocabulary;

    @BeforeEach
    void setUp() {
        vocabulary = new GenreVocabulary(tmdbClient, igdbClient);
    }

    @Test
    void supportsMoviesTvAndGames() {
        assertThat(vocabulary.supports("movies")).isTrue();
        assertThat(vocabulary.supports("tv")).isTrue();
        assertThat(vocabulary.supports("games")).isTrue();
    }

    @Test
    void doesNotYetSupportBooksSinceItsCuratedTableIsATwoNineOnwardsConcern() {
        assertThat(vocabulary.supports("books")).isFalse();
    }

    @Test
    void doesNotSupportAnUnrecognisedMediaType() {
        assertThat(vocabulary.supports("podcasts")).isFalse();
    }

    @Test
    void genresForMoviesDelegatesToTmdbsMovieGenreList() {
        var genres = List.of(new CatalogFilterOption("28", "Action"));
        when(tmdbClient.movieGenres()).thenReturn(genres);

        assertThat(vocabulary.genresFor("movies")).isEqualTo(genres);
        verifyNoInteractions(igdbClient);
    }

    @Test
    void genresForTvDelegatesToTmdbsTvGenreList() {
        var genres = List.of(new CatalogFilterOption("10759", "Action & Adventure"));
        when(tmdbClient.tvGenres()).thenReturn(genres);

        assertThat(vocabulary.genresFor("tv")).isEqualTo(genres);
        verify(tmdbClient, times(1)).tvGenres();
    }

    @Test
    void genresForGamesDelegatesToIgdbsGenreList() {
        var genres = List.of(new CatalogFilterOption("5", "Shooter"));
        when(igdbClient.genres()).thenReturn(genres);

        assertThat(vocabulary.genresFor("games")).isEqualTo(genres);
        verifyNoInteractions(tmdbClient);
    }

    @Test
    void aSecondCallForTheSameMediaTypeIsServedFromTheCacheRatherThanRefetching() {
        when(tmdbClient.movieGenres()).thenReturn(List.of(new CatalogFilterOption("28", "Action")));

        vocabulary.genresFor("movies");
        vocabulary.genresFor("movies");

        verify(tmdbClient, times(1)).movieGenres();
    }

    @Test
    void differentMediaTypesAreCachedIndependently() {
        when(tmdbClient.movieGenres()).thenReturn(List.of(new CatalogFilterOption("28", "Action")));
        when(tmdbClient.tvGenres()).thenReturn(List.of(new CatalogFilterOption("10759", "Action & Adventure")));

        vocabulary.genresFor("movies");
        vocabulary.genresFor("tv");

        verify(tmdbClient, times(1)).movieGenres();
        verify(tmdbClient, times(1)).tvGenres();
    }

    @Test
    void aFetchFailureIsNotCachedSoTheNextCallRetriesRatherThanStayingGenreless() {
        when(tmdbClient.movieGenres())
            .thenThrow(new CatalogUpstreamException("TMDB", new RuntimeException("boom")))
            .thenReturn(List.of(new CatalogFilterOption("28", "Action")));

        assertThatThrownBy(() -> vocabulary.genresFor("movies")).isInstanceOf(CatalogUpstreamException.class);
        var genres = vocabulary.genresFor("movies");

        assertThat(genres).containsExactly(new CatalogFilterOption("28", "Action"));
        verify(tmdbClient, times(2)).movieGenres();
    }

    @Test
    void genresForAnUnsupportedMediaTypeThrowsRatherThanSilentlyReturningEmpty() {
        assertThatThrownBy(() -> vocabulary.genresFor("books")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(tmdbClient, igdbClient);
    }
}
