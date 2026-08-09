package dev.yzlaboratory.alexandrea.auth.mail;

import java.time.Duration;

public final class PasswordResetMail {

    private PasswordResetMail() {}

    public static MailMessage build(String to, String resetUrl, Duration validFor) {
        var body = """
            We received a request to reset your Alexandrea password.

            Choose a new password here:

            %s

            This link is single-use and expires in %s. If you did not
            request it, you can ignore this message — your password is
            unchanged.
            """.formatted(resetUrl, DurationCopy.hours(validFor));
        return new MailMessage(to, "Reset your Alexandrea password", body);
    }
}
