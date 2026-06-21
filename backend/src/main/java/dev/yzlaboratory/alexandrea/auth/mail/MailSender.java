package dev.yzlaboratory.alexandrea.auth.mail;

/**
 * The outbound transactional-email port (ADR 0021).
 *
 * <p>One narrow method — render-and-send one {@link MailMessage} — so the auth
 * service depends on an outbox, not on a provider. The dev profile binds a
 * logging implementation that prints the link; the SES implementation is its own
 * slice and slots in behind this same interface with no caller change.
 * Templating lives behind the port (see {@code VerificationMail}) rather than in
 * callers, so a new mail kind is a new builder, not a new method here.
 */
public interface MailSender {

    void send(MailMessage message);
}
