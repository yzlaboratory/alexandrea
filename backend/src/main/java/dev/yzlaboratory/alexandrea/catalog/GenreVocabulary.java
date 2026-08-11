package dev.yzlaboratory.alexandrea.catalog;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * TMDB's Movies and TV genre enums and IGDB's genre enum (ADR 0013) — fetched
 * once per media type and cached for the process lifetime, since these
 * provider-side enums are effectively static. A failed fetch is not cached,
 * so the next call retries rather than wedging a media type genre-less until
 * the next redeploy.
 *
 * <p>Books has no entry here yet (#43, blocked on this ticket, adds its
 * curated alias table); {@link #supports} is the single place that will grow
 * to include it, so {@link CatalogService}'s capability payload picks it up
 * without restructuring.
 */
@Component
public class GenreVocabulary {

    private static final Set<String> SUPPORTED_MEDIA_TYPES =
        Set.of(TmdbClient.MOVIES_MEDIA_TYPE, TmdbClient.TV_MEDIA_TYPE, IgdbClient.GAMES_MEDIA_TYPE);

    private final TmdbClient tmdbClient;
    private final IgdbClient igdbClient;
    private final ConcurrentHashMap<String, List<CatalogFilterOption>> cache = new ConcurrentHashMap<>();

    public GenreVocabulary(TmdbClient tmdbClient, IgdbClient igdbClient) {
        this.tmdbClient = tmdbClient;
        this.igdbClient = igdbClient;
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

    private List<CatalogFilterOption> fetchGenres(String mediaType) {
        return switch (mediaType) {
            case TmdbClient.MOVIES_MEDIA_TYPE -> tmdbClient.movieGenres();
            case TmdbClient.TV_MEDIA_TYPE -> tmdbClient.tvGenres();
            case IgdbClient.GAMES_MEDIA_TYPE -> igdbClient.genres();
            default -> throw new IllegalArgumentException("No genre vocabulary for media type: " + mediaType);
        };
    }
}
