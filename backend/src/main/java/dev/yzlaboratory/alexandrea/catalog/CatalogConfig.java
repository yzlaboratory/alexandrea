package dev.yzlaboratory.alexandrea.catalog;

import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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

    @Bean
    public RestClient tmdbRestClient(RestClient.Builder builder, CatalogProperties properties) {
        return builder.baseUrl(properties.tmdb().baseUrl()).build();
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
