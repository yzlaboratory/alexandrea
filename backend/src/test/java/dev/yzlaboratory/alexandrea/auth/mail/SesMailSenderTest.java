package dev.yzlaboratory.alexandrea.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

/**
 * {@link SesV2Client} is mocked directly (an AWS SDK v2 client is an
 * interface) so these assertions need no real network call or credentials.
 */
@ExtendWith(MockitoExtension.class)
class SesMailSenderTest {

    @Mock
    private SesV2Client sesClient;

    @Test
    void sendSetsTheConfigurationSetAndTheMailSubdomainSenderIdentity() {
        var sender = new SesMailSender(sesClient, new MailProperties("alexandrea-prod-mail", null));

        sender.send(new MailMessage("recipient@example.com", "Subject line", "Body text"));

        var captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(captor.capture());
        var request = captor.getValue();
        assertThat(request.configurationSetName()).isEqualTo("alexandrea-prod-mail");
        assertThat(request.fromEmailAddress()).isEqualTo("noreply@mail.alexandrea.app");
        assertThat(request.replyToAddresses()).containsExactly("noreply@mail.alexandrea.app");
        assertThat(request.destination().toAddresses()).containsExactly("recipient@example.com");
        assertThat(request.content().simple().subject().data()).isEqualTo("Subject line");
        assertThat(request.content().simple().body().text().data()).isEqualTo("Body text");
    }

    @Test
    void aMissingConfigurationSetFailsFastAtConstructionRatherThanSendingSilently() {
        assertThatThrownBy(() -> new SesMailSender(sesClient, new MailProperties(null, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ses-configuration-set");

        verifyNoInteractions(sesClient);
    }

    @Test
    void aBlankConfigurationSetAlsoFailsFast() {
        assertThatThrownBy(() -> new SesMailSender(sesClient, new MailProperties("   ", null)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSesClientFailureIsNotSwallowed() {
        var sender = new SesMailSender(sesClient, new MailProperties("alexandrea-prod-mail", null));
        var failure = SesV2Exception.builder().message("simulated SES outage").build();
        when(sesClient.sendEmail(any(SendEmailRequest.class))).thenThrow(failure);

        // MailDispatcher.sendBestEffort is the layer that catches and logs —
        // SesMailSender itself must let the failure propagate, not swallow it.
        assertThatThrownBy(() -> sender.send(new MailMessage("recipient@example.com", "Subject", "Body")))
            .isSameAs(failure);
    }
}
