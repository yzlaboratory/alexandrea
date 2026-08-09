package dev.yzlaboratory.alexandrea.auth;

import java.util.Locale;

/**
 * The single home for email case/whitespace normalisation, so every keyed
 * lookup that treats email as an identity — user rows, rate-limit buckets —
 * agrees on the same identity for the same address.
 */
final class Emails {

    private Emails() {}

    static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
