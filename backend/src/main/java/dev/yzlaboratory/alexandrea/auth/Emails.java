package dev.yzlaboratory.alexandrea.auth;

import java.util.Locale;

/**
 * The single home for email case/whitespace normalisation, so every keyed
 * lookup that treats email as an identity — user rows, rate-limit buckets,
 * the mail package's unsendable-address list — agrees on the same identity
 * for the same address. Public because {@code auth.mail} needs the same
 * normalisation and is a different package from this one.
 */
public final class Emails {

    private Emails() {}

    public static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
