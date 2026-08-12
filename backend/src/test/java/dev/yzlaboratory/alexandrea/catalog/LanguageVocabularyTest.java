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
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class LanguageVocabularyTest {

    @Mock
    private IgdbClient igdbClient;

    private LanguageVocabulary vocabulary;

    @BeforeEach
    void setUp() {
        vocabulary = new LanguageVocabulary(igdbClient, new ObjectMapper());
    }

    @Test
    void supportsOriginalLanguageForMoviesAndTv() {
        assertThat(vocabulary.supportsOriginalLanguage("movies")).isTrue();
        assertThat(vocabulary.supportsOriginalLanguage("tv")).isTrue();
    }

    @Test
    void doesNotSupportOriginalLanguageForBooksOrGames() {
        assertThat(vocabulary.supportsOriginalLanguage("books")).isFalse();
        assertThat(vocabulary.supportsOriginalLanguage("games")).isFalse();
    }

    @Test
    void originalLanguageOptionsForMoviesReturnsTheCuratedIso6391List() {
        var options = vocabulary.originalLanguageOptionsFor("movies");

        assertThat(options).contains(new CatalogFilterOption("en", "English"));
        assertThat(options).contains(new CatalogFilterOption("ja", "Japanese"));
        verifyNoInteractions(igdbClient);
    }

    @Test
    void originalLanguageOptionsForMoviesAndTvAreTheSameSharedVocabulary() {
        assertThat(vocabulary.originalLanguageOptionsFor("movies"))
            .isEqualTo(vocabulary.originalLanguageOptionsFor("tv"));
    }

    @Test
    void originalLanguageOptionsForAnUnsupportedMediaTypeThrowsRatherThanSilentlyReturningEmpty() {
        assertThatThrownBy(() -> vocabulary.originalLanguageOptionsFor("books")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyCuratedOriginalLanguageHasATwoLetterIso6391Value() {
        assertThat(vocabulary.originalLanguageOptionsFor("movies"))
            .allSatisfy(option -> assertThat(option.value()).hasSize(2));
    }

    @Test
    void supportsAvailableInLanguageForGamesOnlySoFar() {
        assertThat(vocabulary.supportsAvailableInLanguage("games")).isTrue();
        assertThat(vocabulary.supportsAvailableInLanguage("books")).isFalse();
        assertThat(vocabulary.supportsAvailableInLanguage("movies")).isFalse();
        assertThat(vocabulary.supportsAvailableInLanguage("tv")).isFalse();
    }

    @Test
    void availableInLanguageOptionsForGamesDelegatesToIgdbsLanguageEnum() {
        var languages = List.of(new CatalogFilterOption("1", "English"), new CatalogFilterOption("2", "German"));
        when(igdbClient.languages()).thenReturn(languages);

        assertThat(vocabulary.availableInLanguageOptionsFor("games")).isEqualTo(languages);
    }

    @Test
    void aSecondCallForGamesIsServedFromTheCacheRatherThanRefetching() {
        when(igdbClient.languages()).thenReturn(List.of(new CatalogFilterOption("1", "English")));

        vocabulary.availableInLanguageOptionsFor("games");
        vocabulary.availableInLanguageOptionsFor("games");

        verify(igdbClient, times(1)).languages();
    }

    @Test
    void aFailedGamesLanguageFetchIsNotCachedSoTheNextCallRetries() {
        when(igdbClient.languages())
            .thenThrow(new CatalogUpstreamException("IGDB", new RuntimeException("boom")))
            .thenReturn(List.of(new CatalogFilterOption("1", "English")));

        assertThatThrownBy(() -> vocabulary.availableInLanguageOptionsFor("games")).isInstanceOf(CatalogUpstreamException.class);
        var languages = vocabulary.availableInLanguageOptionsFor("games");

        assertThat(languages).containsExactly(new CatalogFilterOption("1", "English"));
        verify(igdbClient, times(2)).languages();
    }

    @Test
    void availableInLanguageOptionsForAnUnsupportedMediaTypeThrowsRatherThanSilentlyReturningEmpty() {
        assertThatThrownBy(() -> vocabulary.availableInLanguageOptionsFor("books")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(igdbClient);
    }
}
