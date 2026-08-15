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
 * <p>Both caches are also size-bounded, per ADR 0026. {@code MAX_PAGES} is
 * a generous multiple of TMDB's own 500-page cap on the "popular" feed, and
 * {@code MAX_ITEMS} covers every item that many pages could ever
 * reference, so eviction here is a genuine backstop, not a limit expected to
 * bind in normal operation.
 *
 * <p>Each layer is backed by <em>two</em> Caffeine caches sharing one
 * key: a 7-day one for the TTL-respecting {@code getItem}/{@code getPage}
 * reads, and a longer-lived one purely for the "return regardless of TTL"
 * reads ADR 0015's stale-while-error path needs. Caffeine's own
 * {@code expireAfterWrite} makes an expired entry invisible to every read
 * once its TTL elapses, so a single cache cannot serve both "is this still
 * fresh" and "give me whatever's there even if it's not" — hence the
 * second, longer-lived cache rather than tracking write time by hand.
 * {@code putItem}/{@code putPage} write both; only the stale bound differs.
 */
public class CatalogCache {

    private static final Duration TTL = Duration.ofDays(7);
    // A plain multiple of the freshness TTL: long enough that a multi-day
    // upstream outage still finds a servable row, short enough to eventually
    // reclaim entries nobody has successfully refreshed in a long time. Not
    // an ADR-pinned number — see the type-level note above.
    private static final Duration STALE_TTL = TTL.multipliedBy(4);
    private static final long MAX_PAGES = 2_000;
    private static final long MAX_ITEMS = 50_000;

    private final Cache<String, CatalogItem> items;
    private final Cache<String, CatalogItem> staleItems;
    private final Cache<String, CatalogPageResult> pages;
    private final Cache<String, CatalogPageResult> stalePages;

    public CatalogCache(Ticker ticker) {
        this.items = cache(ticker, TTL, MAX_ITEMS);
        this.staleItems = cache(ticker, STALE_TTL, MAX_ITEMS);
        this.pages = cache(ticker, TTL, MAX_PAGES);
        this.stalePages = cache(ticker, STALE_TTL, MAX_PAGES);
    }

    private static <V> Cache<String, V> cache(Ticker ticker, Duration ttl, long maxSize) {
        return Caffeine.newBuilder().ticker(ticker).expireAfterWrite(ttl).maximumSize(maxSize).build();
    }

    public Optional<CatalogItem> getItem(String key) {
        return Optional.ofNullable(items.getIfPresent(key));
    }

    /** Stale-while-error fallback read (ADR 0015): ignores the 7-day freshness TTL. */
    public Optional<CatalogItem> getItemRegardlessOfTtl(String key) {
        return Optional.ofNullable(staleItems.getIfPresent(key));
    }

    public void putItem(String key, CatalogItem item) {
        items.put(key, item);
        staleItems.put(key, item);
    }

    public Optional<CatalogPageResult> getPage(String key) {
        return Optional.ofNullable(pages.getIfPresent(key));
    }

    /** Stale-while-error fallback read (ADR 0015): ignores the 7-day freshness TTL. */
    public Optional<CatalogPageResult> getPageRegardlessOfTtl(String key) {
        return Optional.ofNullable(stalePages.getIfPresent(key));
    }

    public void putPage(String key, CatalogPageResult page) {
        pages.put(key, page);
        stalePages.put(key, page);
    }

    /**
     * Atomic get-or-compute for a page-cache miss: Caffeine guarantees
     * {@code compute} runs at most once per key even under concurrent
     * callers, unlike a separate {@code getPage(key).orElseGet(...)} +
     * {@code putPage(...)} pair, which lets two concurrent misses on the
     * same never-cached page both call through to {@code compute} (a
     * cache-stampede: two upstream calls and two blocked request threads
     * for what should cost one). The stale-fallback cache is populated here
     * too, but only once {@code compute} actually succeeds — a thrown
     * exception reaches the caller with neither cache touched, so a failed
     * refresh can never overwrite a still-good stale entry.
     */
    public CatalogPageResult getOrComputePage(String key, Supplier<CatalogPageResult> compute) {
        return pages.get(key, ignoredKey -> {
            var computed = compute.get();
            stalePages.put(key, computed);
            return computed;
        });
    }

    /** {@code provider|externalId|mediaType}, per ADR 0007's per-item key shape. */
    public static String itemKey(String provider, String externalId, String mediaType) {
        return String.join("|", provider, externalId, mediaType);
    }

    /**
     * {@code provider|mediaType|feedOrQuery|filters|sort|page}, per ADR 0007's
     * feed/query page key shape. {@code filters} and {@code sort} are empty
     * for the plain popular feed; a sorted, filtered, and/or searched page
     * fills them in so it never collides with — or is served by — a
     * different combination's cache entry.
     */
    public static String pageKey(
        String provider, String mediaType, String feedOrQuery, String filters, String sort, int page
    ) {
        return String.join("|", provider, mediaType, feedOrQuery, filters, sort, String.valueOf(page));
    }
}
