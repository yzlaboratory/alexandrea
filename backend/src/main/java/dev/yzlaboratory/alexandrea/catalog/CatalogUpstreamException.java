package dev.yzlaboratory.alexandrea.catalog;

/**
 * Signals that {@code provider} could not be reached right now — either a
 * failed call ({@link #CatalogUpstreamException(String, Throwable)}) or a
 * circuit breaker already open for it ({@link #CatalogUpstreamException(String)},
 * no call attempted). {@link CatalogService} decides what this becomes for
 * the caller: swallowed by a stale-cache fallback, or, on a genuine cold
 * miss, surfaced as this same generic "temporarily unavailable" outcome
 * either way (ADR 0015).
 */
public class CatalogUpstreamException extends RuntimeException {

    public CatalogUpstreamException(String provider) {
        super(provider + " catalog is temporarily unavailable");
    }

    public CatalogUpstreamException(String provider, Throwable cause) {
        super(provider + " catalog is temporarily unavailable", cause);
    }
}
