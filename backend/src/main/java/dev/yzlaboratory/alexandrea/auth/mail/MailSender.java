package dev.yzlaboratory.alexandrea.auth.mail;

/** The outbound-email port (ADR 0021): the auth service depends on this, not on a provider. */
public interface MailSender {

    void send(MailMessage message);
}
