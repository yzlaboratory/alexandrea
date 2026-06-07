package dev.yzlaboratory.alexandrea.auth.mail;

/**
 * One rendered outbound email, ready for any {@link MailSender} to deliver.
 *
 * <p>A flat value object at the port boundary keeps the {@link MailSender}
 * interface free of provider-shaped detail; the template that produced it (e.g.
 * {@link VerificationMail}) owns the wording and link, not the sender.
 */
public record MailMessage(String to, String subject, String body) {}
