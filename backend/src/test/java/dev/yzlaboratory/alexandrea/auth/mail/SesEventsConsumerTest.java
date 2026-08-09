package dev.yzlaboratory.alexandrea.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

/**
 * The AWS polling mechanics around {@link SesEventProcessor}, exercised with
 * a mocked {@link SqsClient} and a mocked processor so no queue or network is
 * needed — the processor's own parsing/idempotency logic has its own tests.
 */
@ExtendWith(MockitoExtension.class)
class SesEventsConsumerTest {

    @Mock
    private SqsClient sqsClient;

    @Mock
    private SesEventProcessor processor;

    @Test
    void aSuccessfullyHandledMessageIsDeleted() {
        var consumer = new SesEventsConsumer(sqsClient, processor, queueProperties());
        var message = message("msg-1", "receipt-1", "body-1");
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
            .thenReturn(responseWith(message))
            .thenReturn(responseWithNoMessages());
        when(processor.handle("body-1")).thenReturn(true);

        consumer.poll();

        var captor = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(sqsClient).deleteMessage(captor.capture());
        assertThat(captor.getValue().queueUrl()).isEqualTo("https://sqs.eu-central-1.amazonaws.com/123/alexandrea-prod-ses-events");
        assertThat(captor.getValue().receiptHandle()).isEqualTo("receipt-1");
    }

    @Test
    void aMessageTheProcessorCouldNotHandleIsLeftOnTheQueue() {
        var consumer = new SesEventsConsumer(sqsClient, processor, queueProperties());
        var message = message("msg-1", "receipt-1", "malformed body");
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
            .thenReturn(responseWith(message))
            .thenReturn(responseWithNoMessages());
        when(processor.handle("malformed body")).thenReturn(false);

        consumer.poll();

        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void aProcessorExceptionLeavesTheMessageOnTheQueueRatherThanPropagating() {
        var consumer = new SesEventsConsumer(sqsClient, processor, queueProperties());
        var message = message("msg-1", "receipt-1", "body-1");
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
            .thenReturn(responseWith(message))
            .thenReturn(responseWithNoMessages());
        when(processor.handle("body-1")).thenThrow(new RuntimeException("simulated DB outage"));

        consumer.poll();

        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void pollDrainsMultipleBatchesInOneTickUntilTheQueueIsEmpty() {
        var consumer = new SesEventsConsumer(sqsClient, processor, queueProperties());
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
            .thenReturn(responseWith(message("msg-1", "receipt-1", "body-1")))
            .thenReturn(responseWith(message("msg-2", "receipt-2", "body-2")))
            .thenReturn(responseWithNoMessages());
        when(processor.handle(any())).thenReturn(true);

        consumer.poll();

        verify(sqsClient, times(3)).receiveMessage(any(ReceiveMessageRequest.class));
        verify(sqsClient, times(2)).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void aMissingQueueUrlFailsFastAtConstruction() {
        assertThatThrownBy(() -> new SesEventsConsumer(sqsClient, processor, new MailProperties(null, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ses-events-queue-url");
    }

    @Test
    void aBlankQueueUrlAlsoFailsFast() {
        assertThatThrownBy(() -> new SesEventsConsumer(sqsClient, processor, new MailProperties(null, "   ")))
            .isInstanceOf(IllegalStateException.class);
    }

    private static MailProperties queueProperties() {
        return new MailProperties(null, "https://sqs.eu-central-1.amazonaws.com/123/alexandrea-prod-ses-events");
    }

    private static Message message(String messageId, String receiptHandle, String body) {
        return Message.builder().messageId(messageId).receiptHandle(receiptHandle).body(body).build();
    }

    private static ReceiveMessageResponse responseWith(Message message) {
        return ReceiveMessageResponse.builder().messages(message).build();
    }

    private static ReceiveMessageResponse responseWithNoMessages() {
        return ReceiveMessageResponse.builder().build();
    }
}
