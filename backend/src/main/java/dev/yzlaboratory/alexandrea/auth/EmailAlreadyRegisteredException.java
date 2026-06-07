package dev.yzlaboratory.alexandrea.auth;

/**
 * Raised when signup targets an email that already has an account.
 *
 * <p>For this tracer bullet the web layer maps it to a generic, enumeration-safe
 * signup response (ADR 0024): the caller is never told the address is taken. The
 * verified/unverified re-signup branching that replaces this plain conflict is
 * #22.
 */
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException() {
        super("Email is already registered");
    }
}
