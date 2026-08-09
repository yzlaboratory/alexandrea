package dev.yzlaboratory.alexandrea.catalog;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The two-layer cache from ADR 0007: a per-item metadata cache and a
 * feed/query page cache, both expiring 7 days after write and held
 * in-memory only (ADR 0026) — a redeploy cold-starts both, which is a
 * designed-for path, not a degraded one (ADR 0001 already requires an
 * empty cache to still produce correct results).
 *
 * <p>The {@link Ticker} is injected (system time in prod) so a test can
 * fast-forward past the TTL without sleeping — the same trick
 * {@code AuthConfig}'s {@link java.time.Clock} bean plays for token expiry.
 *
 * <p>Both caches are also size-bounded (ADR 0026: "Cache size is bounded by
 * Caffeine's own eviction policy (size/weight-based)"). {@code MAX_PAGES} is
 * a generous multiple of TMDB's own 500-page cap on the "popular" feed, and
 * {@code MAX_ITEMS} covers every item that many pages could ever
 * reference, so eviction here is a genuine backstop, not a limit expected to
 * bind in normal operation.
 */
public class CatalogCache {

    private static final Duration TTL = Duration.ofDays(7);
    private static final long MAX_PAGES = 2_000;
    private static final long MAX_ITEMS = 50_000;

    private final Cache<String, CatalogItem> items;
    private final Cache<String, CatalogPageResult> pages;

    public CatalogCache(Ticker ticker) {
        this.items = Caffeine.newBuilder()
            .ticker(ticker)
            .expireAfterWrite(TTL)
            .maximumSize(MAX_ITEMS)
            .build();
        this.pages = Caffeine.newBuilder()
            .ticker(ticker)
            .expireAfterWrite(TTL)
            .maximumSize(MAX_PAGES)
            .build();
    }

    public Optional<CatalogItem> getItem(String key) {
        return Optional.ofNullable(items.getIfPresent(key));
    }

    public void putItem(String key, CatalogItem item) {
        items.put(key, item);
    }

    public Optional<CatalogPageResult> getPage(String key) {
        return Optional.ofNullable(pages.getIfPresent(key));
    }

    public void putPage(String key, CatalogPageResult page) {
        pages.put(key, page);
    }

    /**
     * Atomic get-or-compute for a page-cache miss: Caffeine guarantees
     * {@code compute} runs at most once per key even under concurrent
     * callers, unlike a separate {@code getPage(key).orElseGet(...)} +
     * {@code putPage(...)} pair, which lets two concurrent misses on the
     * same never-cached page both call through to {@code compute} (a
     * cache-stampede: two upstream calls and two blocked request threads
     * for what should cost one).
     */
    public CatalogPageResult getOrComputePage(String key, Supplier<CatalogPageResult> compute) {
        return pages.get(key, ignoredKey -> compute.get());
    }

    /** {@code provider|externalId|mediaType}, per ADR 0007's per-item key shape. */
    public static String itemKey(String provider, String externalId, String mediaType) {
        return String.join("|", provider, externalId, mediaType);
    }

    /**
     * {@code provider|mediaType|feedOrQuery|filters|sort|page}, per ADR 0007's
     * feed/query page key shape. {@code filters} and {@code sort} are empty
     * for this slice's popular feed (#37) — there is no filter/sort/search
     * yet — and will carry real values once #38/#39 add them.
     */
    public static String pageKey(
        String provider, String mediaType, String feedOrQuery, String filters, String sort, int page
    ) {
        return String.join("|", provider, mediaType, feedOrQuery, filters, sort, String.valueOf(page));
    }
}
