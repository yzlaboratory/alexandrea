package dev.yzlaboratory.alexandrea.auth;

/**
 * Length is counted in Unicode code points, not UTF-16 chars, so a passphrase
 * made of astral-plane characters (emoji) is measured as a human would count it.
 * The upper bound is a denial-of-service guard, not a strength rule: Argon2id is
 * deliberately slow, so an unbounded password length is a free way to make the
 * server hash megabytes.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;

    private PasswordPolicy() {}

    public static boolean isAcceptable(String password) {
        if (password == null) {
            return false;
        }
        var length = password.codePointCount(0, password.length());
        return length >= MIN_LENGTH && length <= MAX_LENGTH;
    }
}
