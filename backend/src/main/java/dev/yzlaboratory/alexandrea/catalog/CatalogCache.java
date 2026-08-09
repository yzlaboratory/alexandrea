package dev.yzlaboratory.alexandrea.catalog;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.Optional;

/**
 * The two-layer cache from ADR 0007: a per-entry metadata cache and a
 * feed/query page cache, both expiring 7 days after write and held
 * in-memory only (ADR 0026) — a redeploy cold-starts both, which is a
 * designed-for path, not a degraded one (ADR 0001 already requires an
 * empty cache to still produce correct results).
 *
 * <p>The {@link Ticker} is injected (system time in prod) so a test can
 * fast-forward past the TTL without sleeping — the same trick
 * {@code AuthConfig}'s {@link java.time.Clock} bean plays for token expiry.
 */
public class CatalogCache {

    private static final Duration TTL = Duration.ofDays(7);

    private final Cache<String, CatalogEntry> entries;
    private final Cache<String, CatalogPageResult> pages;

    public CatalogCache(Ticker ticker) {
        this.entries = Caffeine.newBuilder().ticker(ticker).expireAfterWrite(TTL).build();
        this.pages = Caffeine.newBuilder().ticker(ticker).expireAfterWrite(TTL).build();
    }

    public Optional<CatalogEntry> getEntry(String key) {
        return Optional.ofNullable(entries.getIfPresent(key));
    }

    public void putEntry(String key, CatalogEntry entry) {
        entries.put(key, entry);
    }

    public Optional<CatalogPageResult> getPage(String key) {
        return Optional.ofNullable(pages.getIfPresent(key));
    }

    public void putPage(String key, CatalogPageResult page) {
        pages.put(key, page);
    }

    /** {@code provider|externalId|mediaType}, per ADR 0007's per-entry key shape. */
    public static String entryKey(String provider, String externalId, String mediaType) {
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
