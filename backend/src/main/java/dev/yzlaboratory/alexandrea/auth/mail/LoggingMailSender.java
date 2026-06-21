package dev.yzlaboratory.alexandrea.auth.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The dev/no-op {@link MailSender}: logs the message instead of sending it
 * (ADR 0021 — "dev impl logs it").
 *
 * <p>Active when the SES sender is absent, so a developer can read the
 * verification link straight from the application log and complete the flow
 * without an SES account. It is bound to the {@code dev} profile; prod wires the
 * real provider behind the same port.
 */
@Component
@Profile("!prod")
public class LoggingMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailSender.class);

    @Override
    public void send(MailMessage message) {
        log.info("[dev-mail] to={} subject=\"{}\"\n{}", message.to(), message.subject(), message.body());
    }
}
