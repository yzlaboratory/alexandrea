package dev.yzlaboratory.alexandrea.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * TMDB's Movies and TV genre enums and IGDB's genre enum (ADR 0013) — fetched
 * once per media type and cached for the process lifetime, since these
 * provider-side enums are effectively static. A failed fetch is not cached,
 * so the next call retries rather than wedging a media type genre-less until
 * the next redeploy. Books' curated genre-to-subject-alias table (also ADR
 * 0013) is a different mechanism entirely — a maintained resource file
 * rather than a provider call — loaded once at construction since there's no
 * network fetch to fail or cache.
 */
@Component
public class GenreVocabulary {

    private static final Set<String> SUPPORTED_MEDIA_TYPES = Set.of(
        TmdbClient.MOVIES_MEDIA_TYPE, TmdbClient.TV_MEDIA_TYPE, IgdbClient.GAMES_MEDIA_TYPE,
        OpenLibraryClient.BOOKS_MEDIA_TYPE
    );
    private static final String BOOKS_GENRES_RESOURCE = "/catalog/books-genres.json";

    private final TmdbClient tmdbClient;
    private final IgdbClient igdbClient;
    private final ConcurrentHashMap<String, List<CatalogFilterOption>> cache = new ConcurrentHashMap<>();
    private final List<BooksGenre> booksGenres;
    private final Map<String, List<String>> booksGenreAliasesByValue;

    public GenreVocabulary(TmdbClient tmdbClient, IgdbClient igdbClient, ObjectMapper objectMapper) {
        this.tmdbClient = tmdbClient;
        this.igdbClient = igdbClient;
        this.booksGenres = loadBooksGenres(objectMapper);
        this.booksGenreAliasesByValue = booksGenres.stream()
            .collect(Collectors.toMap(BooksGenre::value, genre -> List.copyOf(genre.aliases())));
    }

    public boolean supports(String mediaType) {
        return SUPPORTED_MEDIA_TYPES.contains(mediaType);
    }

    /**
     * The genre enum for {@code mediaType}. Throws {@link
     * CatalogUpstreamException} on a cold-cache fetch failure and {@link
     * IllegalArgumentException} for a media type {@link #supports} rejects —
     * callers are expected to check {@link #supports} first.
     */
    public List<CatalogFilterOption> genresFor(String mediaType) {
        return cache.computeIfAbsent(mediaType, this::fetchGenres);
    }

    /**
     * The OpenLibrary subject aliases (ADR 0013) a curated Books {@code
     * genreValue} matches — empty if it isn't one of the values {@link
     * #genresFor} returns for {@code "books"}. {@link OpenLibraryClient}
     * turns this into the {@code subject:(alias OR alias …)} query fragment;
     * this class owns only the curated-value-to-alias mapping, the same
     * split as TMDB/IGDB where this class resolves the genre value and the
     * provider client owns turning it into that provider's own query syntax.
     */
    public List<String> booksSubjectAliases(String genreValue) {
        return booksGenreAliasesByValue.getOrDefault(genreValue, List.of());
    }

    private List<CatalogFilterOption> fetchGenres(String mediaType) {
        return switch (mediaType) {
            case TmdbClient.MOVIES_MEDIA_TYPE -> tmdbClient.movieGenres();
            case TmdbClient.TV_MEDIA_TYPE -> tmdbClient.tvGenres();
            case IgdbClient.GAMES_MEDIA_TYPE -> igdbClient.genres();
            case OpenLibraryClient.BOOKS_MEDIA_TYPE -> booksGenres.stream()
                .map(genre -> new CatalogFilterOption(genre.value(), genre.label())).toList();
            default -> throw new IllegalArgumentException("No genre vocabulary for media type: " + mediaType);
        };
    }

    private static List<BooksGenre> loadBooksGenres(ObjectMapper objectMapper) {
        try (InputStream resource = GenreVocabulary.class.getResourceAsStream(BOOKS_GENRES_RESOURCE)) {
            if (resource == null) {
                throw new IllegalStateException("Missing bundled resource " + BOOKS_GENRES_RESOURCE);
            }
            return List.of(objectMapper.readValue(resource, BooksGenre[].class));
        } catch (IOException e) {
            throw new IllegalStateException(
                "Could not load the curated Books genre table from " + BOOKS_GENRES_RESOURCE, e);
        }
    }

    private record BooksGenre(String value, String label, List<String> aliases) {}
}
