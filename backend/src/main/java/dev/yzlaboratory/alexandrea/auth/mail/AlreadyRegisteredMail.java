package dev.yzlaboratory.alexandrea.auth.mail;

public final class AlreadyRegisteredMail {

    private AlreadyRegisteredMail() {}

    public static MailMessage build(String to) {
        var body = """
            Someone just tried to sign up for Alexandrea with this email
            address, but you already have an account.

            If this was you, log in as usual. If it wasn't, no action is
            needed — your account and password are unchanged.
            """;
        return new MailMessage(to, "You already have an Alexandrea account", body);
    }
}
