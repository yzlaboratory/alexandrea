package dev.yzlaboratory.alexandrea.auth.mail;

/**
 * Renders the email-verification message (#19).
 *
 * <p>The one mail template the signup tracer bullet needs. It is a separate
 * builder rather than inline string-building in the auth service so the wording
 * and the link shape have a single home, and so the reset/email-change templates
 * (#23, #25) land as sibling builders without touching the {@link MailSender}
 * port. The clickable link is built from the configured SPA URL template with
 * the raw token substituted.
 */
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
