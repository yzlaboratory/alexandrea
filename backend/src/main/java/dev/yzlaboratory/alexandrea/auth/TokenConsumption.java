package dev.yzlaboratory.alexandrea.auth;

/**
 * The web layer collapses {@link Expired} and {@link Rejected} into one
 * enumeration-safe response (ADR 0024); they are kept distinct here so tests and
 * logging can tell the rejection reason apart.
 */
public sealed interface TokenConsumption {

    record Consumed(long userId) implements TokenConsumption {}

    record Expired() implements TokenConsumption {}

    record Rejected() implements TokenConsumption {}
}
