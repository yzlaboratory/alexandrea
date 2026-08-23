package dev.yzlaboratory.alexandrea.catalog;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * The TMDB {@link RestClient} is its own bean (rather than built inline in
 * {@link TmdbClient}) purely so a test can point {@code alexandrea.catalog}
 * at a throwaway local server via a {@code @DynamicPropertySource} override —
 * the same "swap the outer boundary, keep the rest of the wiring real"
 * approach {@code AuthEndpointTest} already uses for its SQLite datasource.
 */
@Configuration
@EnableConfigurationProperties(CatalogProperties.class)
public class CatalogConfig {

    // Finite and generous rather than Spring's out-of-the-box "wait
    // forever": every RestClient below hits a third-party API over the open
    // internet, and a provider that accepts the connection but then never
    // responds would otherwise hang the calling thread indefinitely —
    // ProviderCircuitBreaker only reacts to an actual failure/exception, so
    // a hang alone would never trip it. 5s to connect / 10s to read is
    // comfortable for a slow-but-healthy response from TMDB/OpenLibrary/IGDB
    // without leaving a request (or its caller) stuck for minutes.
    //
    // Exposed as an HttpClientSettings bean rather than passed to each
    // RestClient.Builder by hand: RestClientAutoConfiguration already picks
    // up any HttpClientSettings bean and applies it when it builds each
    // (prototype-scoped) RestClient.Builder injected below, so every
    // provider client gets its own request factory carrying these timeouts
    // without this class wiring the request factory itself.
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    public HttpClientSettings catalogHttpClientSettings() {
        return HttpClientSettings.defaults().withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    @Bean
    public RestClient tmdbRestClient(RestClient.Builder builder, CatalogProperties properties) {
        return builder.baseUrl(properties.tmdb().baseUrl()).build();
    }

    @Bean
    public RestClient openLibraryRestClient(RestClient.Builder builder, CatalogProperties properties) {
        return builder.baseUrl(properties.openLibrary().baseUrl()).build();
    }

    @Bean
    public RestClient igdbGamesRestClient(RestClient.Builder builder, CatalogProperties properties) {
        return builder.baseUrl(properties.igdb().baseUrl()).build();
    }

    @Bean
    public RestClient igdbTwitchRestClient(RestClient.Builder builder, CatalogProperties properties) {
        return builder.baseUrl(properties.igdb().twitchBaseUrl()).build();
    }

    @Bean
    public Ticker catalogCacheTicker() {
        return Ticker.systemTicker();
    }

    @Bean
    public CatalogCache catalogCache(Ticker catalogCacheTicker) {
        return new CatalogCache(catalogCacheTicker);
    }
}
