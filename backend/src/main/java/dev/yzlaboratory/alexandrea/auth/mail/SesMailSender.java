package dev.yzlaboratory.alexandrea.auth.mail;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

@Component
@Profile("prod")
public class SesMailSender implements MailSender {

    private static final String SENDER_ADDRESS = "noreply@mail.alexandrea.app";

    private final SesV2Client sesClient;
    private final String configurationSet;

    public SesMailSender(SesV2Client sesClient, MailProperties properties) {
        this.sesClient = sesClient;
        this.configurationSet = requireConfigurationSet(properties);
    }

    @Override
    public void send(MailMessage message) {
        var request = SendEmailRequest.builder()
            .configurationSetName(configurationSet)
            .fromEmailAddress(SENDER_ADDRESS)
            .replyToAddresses(SENDER_ADDRESS)
            .destination(Destination.builder().toAddresses(message.to()).build())
            .content(EmailContent.builder()
                .simple(Message.builder()
                    .subject(Content.builder().data(message.subject()).build())
                    .body(Body.builder().text(Content.builder().data(message.body()).build()).build())
                    .build())
                .build())
            .build();
        sesClient.sendEmail(request);
    }

    // A missing configuration set means every send skips per-send suppression
    // and emits no bounce/complaint events to SesEventsConsumer — worth
    // refusing to start over sending mail that looks fine but isn't.
    private static String requireConfigurationSet(MailProperties properties) {
        var configurationSet = properties.sesConfigurationSet();
        if (configurationSet == null || configurationSet.isBlank()) {
            throw new IllegalStateException(
                "alexandrea.mail.ses-configuration-set is required in prod");
        }
        return configurationSet;
    }
}
