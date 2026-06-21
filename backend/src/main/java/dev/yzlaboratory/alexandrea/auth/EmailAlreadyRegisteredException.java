package dev.yzlaboratory.alexandrea.auth;

/**
 * Raised when signup targets an email that already has an account.
 *
 * <p>The web layer maps it to a generic, enumeration-safe signup response
 * (ADR 0024): the caller is never told the address is taken.
 */
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException() {
        super("Email is already registered");
    }
}
