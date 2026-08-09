package dev.yzlaboratory.alexandrea.auth.mail;

/** Sent to the previous address once a change completes; informational only, mirrors AlreadyRegisteredMail's shape. */
public final class EmailChangedMail {

    private EmailChangedMail() {}

    public static MailMessage build(String to, String newEmail) {
        var body = """
            The email on your Alexandrea account was changed to %s.

            If this was you, no action is needed. If it wasn't, someone
            with your password made this change — this address no
            longer has access to the account.
            """.formatted(newEmail);
        return new MailMessage(to, "Your Alexandrea email was changed", body);
    }
}
