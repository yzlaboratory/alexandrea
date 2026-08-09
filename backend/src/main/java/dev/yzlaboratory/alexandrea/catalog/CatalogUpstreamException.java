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
        // No fault actually occurred here — the breaker short-circuited
        // before any call was attempted — and this constructor runs on
        // every request during an open window, so skip the stack-trace
        // capture the default super(String) constructor would otherwise
        // pay for on that path.
        super(provider + " catalog is temporarily unavailable", null, false, false);
    }

    public CatalogUpstreamException(String provider, Throwable cause) {
        super(provider + " catalog is temporarily unavailable", cause);
    }
}
