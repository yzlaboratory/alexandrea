package dev.yzlaboratory.alexandrea.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Original-language and available-in-language filter vocabularies (ADR
 * 0018). Original language is offered only for Movies/TV: TMDB's {@code
 * with_original_language} accepts any ISO 639-1 code, and rather than mirror
 * TMDB's full {@code /configuration/languages} list (~180 entries, most
 * never spoken in a catalog title) this offers a curated, app-maintained
 * subset — the same "curate rather than mirror the provider's full enum"
 * call ADR 0013 already made for Books' genre filter, applied here for a
 * different reason (list size, not query mechanism). Available-in-language
 * is offered only for Games so far (Books lands in a later slice): IGDB's
 * own native {@code /languages} enum, fetched once and cached for the
 * process lifetime exactly like {@link GenreVocabulary} caches IGDB's genre
 * enum — a fetch failure isn't cached, so the next call retries rather than
 * wedging Games language-less until the next redeploy.
 */
@Component
public class LanguageVocabulary {

    private static final Set<String> ORIGINAL_LANGUAGE_MEDIA_TYPES = Set.of(TmdbClient.MOVIES_MEDIA_TYPE, TmdbClient.TV_MEDIA_TYPE);
    private static final Set<String> AVAILABLE_IN_LANGUAGE_MEDIA_TYPES = Set.of(IgdbClient.GAMES_MEDIA_TYPE);
    private static final String ORIGINAL_LANGUAGES_RESOURCE = "/catalog/original-languages.json";

    private final IgdbClient igdbClient;
    private final List<CatalogFilterOption> originalLanguages;
    private final ConcurrentHashMap<String, List<CatalogFilterOption>> availableInLanguageCache = new ConcurrentHashMap<>();

    public LanguageVocabulary(IgdbClient igdbClient, ObjectMapper objectMapper) {
        this.igdbClient = igdbClient;
        this.originalLanguages = loadCuratedLanguages(objectMapper, ORIGINAL_LANGUAGES_RESOURCE);
    }

    public boolean supportsOriginalLanguage(String mediaType) {
        return ORIGINAL_LANGUAGE_MEDIA_TYPES.contains(mediaType);
    }

    /** The curated ISO 639-1 vocabulary {@code with_original_language} accepts. Throws for a media type {@link #supportsOriginalLanguage} rejects. */
    public List<CatalogFilterOption> originalLanguageOptionsFor(String mediaType) {
        if (!supportsOriginalLanguage(mediaType)) {
            throw new IllegalArgumentException("No original-language vocabulary for media type: " + mediaType);
        }
        return originalLanguages;
    }

    public boolean supportsAvailableInLanguage(String mediaType) {
        return AVAILABLE_IN_LANGUAGE_MEDIA_TYPES.contains(mediaType);
    }

    /** IGDB's native language enum for Games. Throws for a media type {@link #supportsAvailableInLanguage} rejects. */
    public List<CatalogFilterOption> availableInLanguageOptionsFor(String mediaType) {
        if (!supportsAvailableInLanguage(mediaType)) {
            throw new IllegalArgumentException("No available-in-language vocabulary for media type: " + mediaType);
        }
        return availableInLanguageCache.computeIfAbsent(mediaType, ignored -> igdbClient.languages());
    }

    private static List<CatalogFilterOption> loadCuratedLanguages(ObjectMapper objectMapper, String resource) {
        try (InputStream stream = LanguageVocabulary.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled resource " + resource);
            }
            return List.of(objectMapper.readValue(stream, CatalogFilterOption[].class));
        } catch (IOException e) {
            throw new IllegalStateException("Could not load the curated language table from " + resource, e);
        }
    }
}
