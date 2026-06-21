package dev.yzlaboratory.alexandrea.auth.mail;

public final class VerificationMail {

    private VerificationMail() {}

    public static MailMessage build(String to, String verificationUrl) {
        var body = """
            Welcome to Alexandrea.

            Confirm this email address to activate your account:

            %s

            This link is single-use and expires in 24 hours. If you did not
            request it, you can ignore this message.
            """.formatted(verificationUrl);
        return new MailMessage(to, "Verify your Alexandrea email", body);
    }
}
