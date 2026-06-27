package dev.yzlaboratory.alexandrea.auth;

public sealed interface TokenConsumption {

    record Consumed(long userId) implements TokenConsumption {}

    record Expired() implements TokenConsumption {}

    record Rejected() implements TokenConsumption {}
}
