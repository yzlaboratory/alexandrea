package dev.yzlaboratory.alexandrea.auth.mail;

import java.time.Duration;

public final class VerificationMail {

    private VerificationMail() {}

    public static MailMessage build(String to, String verificationUrl, Duration validFor) {
        var body = """
            Welcome to Alexandrea.

            Confirm this email address to activate your account:

            %s

            This link is single-use and expires in %s. If you did not
            request it, you can ignore this message.
            """.formatted(verificationUrl, DurationCopy.hours(validFor));
        return new MailMessage(to, "Verify your Alexandrea email", body);
    }
}
