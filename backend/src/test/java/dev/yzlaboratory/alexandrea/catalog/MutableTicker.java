package dev.yzlaboratory.alexandrea.catalog;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link Ticker} a test can advance, so {@link CatalogCache}'s 7-day TTL
 * is provable without sleeping — the Caffeine-cache equivalent of the auth
 * module's {@code MutableClock}.
 */
final class MutableTicker implements Ticker {

    private final AtomicLong nanos = new AtomicLong();

    void advance(Duration by) {
        nanos.addAndGet(by.toNanos());
    }

    @Override
    public long read() {
        return nanos.get();
    }
}
