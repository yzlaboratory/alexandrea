package dev.yzlaboratory.alexandrea.auth;

/** Raised when a submitted password is outside the {@link PasswordPolicy} length bounds. */
public class PasswordPolicyViolationException extends RuntimeException {

    public PasswordPolicyViolationException() {
        super("Password must be between %d and %d characters"
            .formatted(PasswordPolicy.MIN_LENGTH, PasswordPolicy.MAX_LENGTH));
    }
}
