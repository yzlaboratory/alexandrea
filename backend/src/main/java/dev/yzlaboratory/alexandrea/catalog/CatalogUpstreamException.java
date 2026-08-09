package dev.yzlaboratory.alexandrea.catalog;

/**
 * Wraps a failed provider call (network failure or non-2xx response) on a
 * cache miss. #38 adds the stale-cache fallback and per-provider circuit
 * breaker (ADR 0015) this slice (#37) doesn't have yet — for now, a cold
 * miss against a down provider simply fails the request.
 */
public class CatalogUpstreamException extends RuntimeException {

    public CatalogUpstreamException(String provider, Throwable cause) {
        super(provider + " catalog is temporarily unavailable", cause);
    }
}
