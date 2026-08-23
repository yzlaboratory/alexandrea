package dev.yzlaboratory.alexandrea.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Talks to IGDB's {@code /games} endpoint — sorted by {@code
 * total_rating_count desc} for the popular feed, by the caller's chosen
 * field/direction for the sorted/discover feed, or with an Apicalypse
 * {@code search "…"} clause for title search (ADR 0018's "nothing applied",
 * "filter/sort applied", and "text search active" rows) — and maps its
 * response into the common {@link CatalogItem} shape (ADR 0001). IGDB
 * authenticates via a Twitch client-credentials token: this client fetches
 * one lazily on first use and caches it in memory, refetching only on
 * expiry or a 401 from {@code /games} — there is no scheduled refresh job.
 */
@Component
public class IgdbClient {

    private static final Logger LOG = LoggerFactory.getLogger(IgdbClient.class);

    // Package-private (not private): CatalogService references these
    // directly for its dispatch and cache-key building rather than
    // redeclaring its own copy that could silently drift out of sync.
    static final String PROVIDER = "IGDB";
    static final String GAMES_MEDIA_TYPE = "games";
    private static final double IGDB_RATING_SCALE = 100.0;
    private static final int PAGE_SIZE = 20;
    private static final String CLIENT_ID_HEADER = "Client-ID";
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cntrl}");
    private static final ParameterizedTypeReference<List<IgdbGame>> GAME_LIST_TYPE = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<IgdbGenre>> GENRE_LIST_TYPE = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<IgdbLanguage>> LANGUAGE_LIST_TYPE = new ParameterizedTypeReference<>() {};
    // Shared by /genres and /languages: both are the same shape of request
    // (every {id, name} entry, sorted by name) against a different endpoint.
    private static final String NAME_SORTED_LIST_REQUEST_BODY = """
        fields name;
        limit 500;
        sort name asc;
        """;

    private final RestClient gamesRestClient;
    private final RestClient twitchRestClient;
    private final CatalogProperties properties;
    private final Clock clock;

    private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();

    public IgdbClient(RestClient igdbGamesRestClient, RestClient igdbTwitchRestClient, CatalogProperties properties, Clock clock) {
        this.gamesRestClient = igdbGamesRestClient;
        this.twitchRestClient = igdbTwitchRestClient;
        this.properties = properties;
        this.clock = clock;
    }

    // IGDB's own default feed is already "sorted by popularity desc" (ADR
    // 0018's "nothing applied" row), so this is the same request discoverGames
    // would build for that exact (sortKey, direction) pair with no filters.
    public CatalogPageResult popularGames(int page) {
        return discoverGames(CatalogSort.POPULARITY, CatalogSort.DESCENDING, null, null, page);
    }

    // ADR 0018's "filter/sort applied" row: same /games endpoint as popular,
    // parameterized by the caller's chosen sort field/direction instead of
    // the fixed total_rating_count desc, with an optional genre (IGDB's
    // native genre id, ADR 0013's "native enum" for Games) and an optional
    // availableInLanguage (IGDB's native language id, ADR 0018's available-
    // in-language filter for Games) each added as their own `where` clause
    // when present — combined with `&` when both are given, so the result
    // narrows to entries matching both rather than either.
    public CatalogPageResult discoverGames(String sortKey, String direction, String genre, String availableInLanguage, int page) {
        return fetchPage(page, discoverRequestBody(sortKey, direction, genre, availableInLanguage, page));
    }

    // ADR 0018's "text search active" row: IGDB's search is an Apicalypse
    // "search" clause on the same /games endpoint, not a separate one — and
    // (per the ADR's "Behavior under active text search" section) cannot be
    // combined with an explicit sort, so discoverRequestBody's sort clause is
    // dropped rather than reused here.
    public CatalogPageResult search(String query, int page) {
        return search(query, null, null, page);
    }

    // The filtered counterpart of the overload above: a `search` clause
    // combines with a `where` filter fine (unlike an explicit `sort`, which
    // still has no place in this request body), so Games' genre and
    // available-in-language filters stay live during a search per ADR
    // 0018's "Behavior under active text search" — CatalogService passes
    // null for whichever of the two isn't currently applied.
    public CatalogPageResult search(String query, String genre, String availableInLanguage, int page) {
        return fetchPage(page, searchRequestBody(query, genre, availableInLanguage, page));
    }

    /** IGDB's native genre enum (ADR 0013), for {@link GenreVocabulary} to cache. */
    public List<CatalogFilterOption> genres() {
        var genres = postApicalypse("/genres", NAME_SORTED_LIST_REQUEST_BODY, GENRE_LIST_TYPE, false);
        return genres.stream().map(genre -> new CatalogFilterOption(String.valueOf(genre.id()), genre.name())).toList();
    }

    /** IGDB's native language enum (ADR 0018's available-in-language filter for Games), for {@link LanguageVocabulary} to cache. */
    public List<CatalogFilterOption> languages() {
        var languages = postApicalypse("/languages", NAME_SORTED_LIST_REQUEST_BODY, LANGUAGE_LIST_TYPE, false);
        return languages.stream().map(language -> new CatalogFilterOption(String.valueOf(language.id()), language.name())).toList();
    }

    // Shared by the popular feed and search: same 401-retry-once handling,
    // same response mapping — only the Apicalypse request body differs.
    private CatalogPageResult fetchPage(int page, String requestBody) {
        var games = postApicalypse("/games", requestBody, GAME_LIST_TYPE, false);
        var items = games.stream().map(this::toItem).toList();
        // IGDB's /games response carries no total-count field, so a full
        // page is the only available signal that more might follow, the
        // same heuristic OpenLibraryClient uses for the same reason.
        var hasMore = items.size() >= PAGE_SIZE;
        return new CatalogPageResult(items, page, hasMore);
    }

    // Shared by every Apicalypse endpoint this client calls (/games,
    // /genres): same 401-retry-once handling, same "empty body on a genuine
    // 200" tolerance — only the path, request body, and response element
    // type differ per call site.
    private <T> List<T> postApicalypse(
        String path, String requestBody, ParameterizedTypeReference<List<T>> responseType, boolean isRetryAfterUnauthorized
    ) {
        try {
            var response = gamesRestClient.post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                .header(CLIENT_ID_HEADER, properties.igdb().clientId())
                .contentType(MediaType.TEXT_PLAIN)
                .body(requestBody)
                .retrieve()
                .body(responseType);
            return response != null ? response : List.of();
        } catch (HttpClientErrorException.Unauthorized unauthorized) {
            if (isRetryAfterUnauthorized) {
                throw new CatalogUpstreamException(PROVIDER, unauthorized);
            }
            LOG.info("IGDB rejected the cached Twitch token; fetching a fresh one and retrying once");
            invalidateToken();
            return postApicalypse(path, requestBody, responseType, true);
        } catch (RestClientException e) {
            throw new CatalogUpstreamException(PROVIDER, e);
        }
    }

    private static String discoverRequestBody(String sortKey, String direction, String genre, String availableInLanguage, int page) {
        return """
            fields name,cover.image_id,first_release_date,total_rating;
            %ssort %s %s;
            limit %d;
            offset %d;
            """.formatted(buildWhereClause(genre, availableInLanguage), igdbSortField(sortKey), direction, PAGE_SIZE, offsetForPage(page));
    }

    // ADR 0018 pins only the default direction's literal Apicalypse clause
    // (e.g. "sort total_rating_count desc") — Apicalypse's own sort syntax
    // accepts either direction keyword for any field, so the opposite
    // direction is the same mechanism with the keyword flipped.
    private static String igdbSortField(String sortKey) {
        return switch (sortKey) {
            case CatalogSort.POPULARITY -> "total_rating_count";
            case CatalogSort.RELEASE_DATE -> "first_release_date";
            case CatalogSort.TITLE -> "name";
            case CatalogSort.EXTERNAL_RATING -> "total_rating";
            default -> throw new IllegalArgumentException("Unsupported sort key: " + sortKey);
        };
    }

    private static String searchRequestBody(String query, String genre, String availableInLanguage, int page) {
        return """
            search "%s";
            %sfields name,cover.image_id,first_release_date,total_rating;
            limit %d;
            offset %d;
            """.formatted(escapeApicalypseString(query), buildWhereClause(genre, availableInLanguage), PAGE_SIZE, offsetForPage(page));
    }

    // Shared by discoverRequestBody and searchRequestBody: an optional genre
    // (IGDB's native genre id, ADR 0013's "native enum" for Games) and an
    // optional availableInLanguage (IGDB's native language id) each become
    // their own `where` sub-clause, combined with `&` when both are given so
    // the result narrows to entries matching both rather than either; with
    // neither given, the `where` line is omitted entirely rather than sent
    // empty.
    private static String buildWhereClause(String genre, String availableInLanguage) {
        var conditions = new ArrayList<String>();
        if (genre != null) {
            conditions.add("genres = (%s)".formatted(genre));
        }
        if (availableInLanguage != null) {
            conditions.add("language_supports.language = (%s)".formatted(availableInLanguage));
        }
        return conditions.isEmpty() ? "" : "where " + String.join(" & ", conditions) + ";\n";
    }

    private static int offsetForPage(int page) {
        return (page - 1) * PAGE_SIZE;
    }

    // A query containing a literal quote or backslash would otherwise break
    // out of the Apicalypse string it's embedded in; a raw control character
    // (e.g. a stray NUL or ESC byte that CatalogService's own whitespace
    // collapse wouldn't catch) has no legitimate role in a title search and
    // is stripped rather than escaped.
    private static String escapeApicalypseString(String value) {
        var withoutControlCharacters = CONTROL_CHARACTERS.matcher(value).replaceAll("");
        return withoutControlCharacters.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private CatalogItem toItem(IgdbGame game) {
        return new CatalogItem(
            PROVIDER,
            String.valueOf(game.id()),
            GAMES_MEDIA_TYPE,
            game.name(),
            coverUrl(game.cover()),
            releaseDate(game.firstReleaseDate()),
            game.totalRating(),
            IGDB_RATING_SCALE
        );
    }

    private String coverUrl(IgdbCover cover) {
        if (cover == null || cover.imageId() == null) {
            return null;
        }
        return properties.igdb().coverBaseUrl() + cover.imageId() + ".jpg";
    }

    private static LocalDate releaseDate(Long firstReleaseDateEpochSeconds) {
        if (firstReleaseDateEpochSeconds == null) {
            return null;
        }
        return Instant.ofEpochSecond(firstReleaseDateEpochSeconds).atZone(ZoneOffset.UTC).toLocalDate();
    }

    // Double-checked against the atomic reference: the common case (a still-
    // valid cached token) returns on a plain get() with no lock at all, so
    // concurrent requests never serialize behind each other just to read an
    // already-good token — only an actual refresh takes the monitor.
    private String accessToken() {
        var token = cachedToken.get();
        if (token != null && !token.isExpiredAt(clock.instant())) {
            return token.accessToken();
        }
        return refreshedAccessToken();
    }

    private synchronized String refreshedAccessToken() {
        var token = cachedToken.get();
        if (token == null || token.isExpiredAt(clock.instant())) {
            token = fetchToken();
            cachedToken.set(token);
        }
        return token.accessToken();
    }

    private synchronized void invalidateToken() {
        cachedToken.set(null);
    }

    private CachedToken fetchToken() {
        try {
            var response = twitchRestClient.post()
                .uri(uriBuilder -> uriBuilder
                    .path("/oauth2/token")
                    .queryParam("client_id", properties.igdb().clientId())
                    .queryParam("client_secret", properties.igdb().clientSecret())
                    .queryParam("grant_type", "client_credentials")
                    .build())
                .retrieve()
                .body(TwitchTokenResponse.class);
            if (response == null) {
                // A real round trip happened here (unlike the breaker-open
                // no-op the single-arg constructor is reserved for), so this
                // needs the cause-carrying constructor even though there's
                // no thrown exception to wrap — a 200 with an empty body is
                // itself the anomaly.
                throw new CatalogUpstreamException(PROVIDER, new IllegalStateException("Twitch token response body was empty"));
            }
            return new CachedToken(response.accessToken(), clock.instant().plusSeconds(response.expiresIn()));
        } catch (RestClientException e) {
            throw new CatalogUpstreamException(PROVIDER, e);
        }
    }

    private record CachedToken(String accessToken, Instant expiresAt) {

        // A margin ahead of the real expiry so a token that's valid when
        // checked here can't still expire mid-flight to IGDB — that would
        // otherwise cost a needless trip through the 401-retry path.
        private static final long EXPIRY_SAFETY_MARGIN_SECONDS = 30;

        boolean isExpiredAt(Instant now) {
            return !now.isBefore(expiresAt.minusSeconds(EXPIRY_SAFETY_MARGIN_SECONDS));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TwitchTokenResponse(@JsonProperty("access_token") String accessToken, @JsonProperty("expires_in") long expiresIn) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IgdbGame(
        long id,
        String name,
        IgdbCover cover,
        @JsonProperty("first_release_date") Long firstReleaseDate,
        @JsonProperty("total_rating") Double totalRating
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IgdbCover(@JsonProperty("image_id") String imageId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IgdbGenre(long id, String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IgdbLanguage(long id, String name) {}
}
