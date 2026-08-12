package dev.yzlaboratory.alexandrea.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LanguageVocabularyTest {

    private LanguageVocabulary vocabulary;

    @BeforeEach
    void setUp() {
        vocabulary = new LanguageVocabulary(new ObjectMapper());
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
}
