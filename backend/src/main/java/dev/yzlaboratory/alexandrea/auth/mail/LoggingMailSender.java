package dev.yzlaboratory.alexandrea.auth.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Logs the verification link instead of sending it, so a developer can complete
 * the flow without an SES account.
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
