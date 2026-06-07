package dev.yzlaboratory.alexandrea.auth;

/**
 * The outcome of presenting a raw token to {@link TokenService#consume}.
 *
 * <p>A sealed set so callers handle every case with an exhaustive {@code switch}
 * (stack.md: closed variant sets are modelled as sealed types). The web layer
 * collapses {@link Expired} and {@link Rejected} into the same enumeration-safe
 * "rejected, offer resend" response (ADR 0024); they are kept distinct here so
 * the rejection reason is available for logging and tests.
 */
public sealed interface TokenConsumption {

    /** The token was live and single-use-valid; it is now consumed for {@code userId}. */
    record Consumed(long userId) implements TokenConsumption {}

    /** The token matched but its 24h window had passed; nothing was consumed. */
    record Expired() implements TokenConsumption {}

    /** No live token matched — unknown, malformed, or already used. */
    record Rejected() implements TokenConsumption {}
}
