package dev.yzlaboratory.alexandrea.auth;

public sealed interface LoginOutcome {

    record Authenticated(User user) implements LoginOutcome {}

    record UnverifiedAccount() implements LoginOutcome {}

    record InvalidCredentials() implements LoginOutcome {}
}
