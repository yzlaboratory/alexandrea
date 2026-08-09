package dev.yzlaboratory.alexandrea.auth;

public sealed interface EmailChangeConsumption {

    record Consumed(long userId, String newEmail) implements EmailChangeConsumption {}

    record Expired() implements EmailChangeConsumption {}

    record Rejected() implements EmailChangeConsumption {}
}
