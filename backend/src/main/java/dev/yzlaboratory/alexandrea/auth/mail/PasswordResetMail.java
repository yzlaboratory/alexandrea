package dev.yzlaboratory.alexandrea.auth.mail;

public final class PasswordResetMail {

    private PasswordResetMail() {}

    public static MailMessage build(String to, String resetUrl) {
        var body = """
            We received a request to reset your Alexandrea password.

            Choose a new password here:

            %s

            This link is single-use and expires in 1 hour. If you did not
            request it, you can ignore this message — your password is
            unchanged.
            """.formatted(resetUrl);
        return new MailMessage(to, "Reset your Alexandrea password", body);
    }
}
