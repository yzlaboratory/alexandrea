package dev.yzlaboratory.alexandrea.auth.mail;

import java.time.Duration;

/** Sent to the requested new address; the account email stays the old one until this link is opened. */
public final class EmailChangeMail {

    private EmailChangeMail() {}

    public static MailMessage build(String to, String confirmUrl, Duration validFor) {
        var body = """
            We received a request to change the email on an Alexandrea
            account to this address.

            Confirm the change here:

            %s

            This link is single-use and expires in %s. If you did not
            request it, you can ignore this message — your account is
            unaffected.
            """.formatted(confirmUrl, DurationCopy.hours(validFor));
        return new MailMessage(to, "Confirm your new Alexandrea email", body);
    }
}
