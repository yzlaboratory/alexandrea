package dev.yzlaboratory.alexandrea.auth.mail;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * Prod-only, matching {@link SesMailSender} — no queue exists in dev/test,
 * and a poll loop hitting AWS during {@code ./gradlew test} is exactly what
 * the profile gate prevents.
 */
@Component
@Profile("prod")
public class SesEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(SesEventsConsumer.class);

    // SQS caps a single receive at 10; long-poll the full 20s so an empty
    // queue doesn't turn this into a tight loop between scheduled ticks.
    private static final int MAX_MESSAGES_PER_RECEIVE = 10;
    private static final int LONG_POLL_WAIT_SECONDS = 20;

    private final SqsClient sqsClient;
    private final SesEventProcessor processor;
    private final String queueUrl;

    public SesEventsConsumer(SqsClient sqsClient, SesEventProcessor processor, MailProperties properties) {
        this.sqsClient = sqsClient;
        this.processor = processor;
        this.queueUrl = requireQueueUrl(properties);
    }

    // Drains the queue on each tick rather than handling one batch per tick,
    // so a backlog (e.g. after downtime) clears in one wake-up instead of
    // trickling out at the schedule's pace.
    @Scheduled(fixedDelay = 30, initialDelay = 30, timeUnit = TimeUnit.SECONDS)
    public void poll() {
        List<Message> received;
        while (!(received = receiveMessages()).isEmpty()) {
            received.forEach(this::handle);
        }
    }

    private List<Message> receiveMessages() {
        var request = ReceiveMessageRequest.builder()
            .queueUrl(queueUrl)
            .maxNumberOfMessages(MAX_MESSAGES_PER_RECEIVE)
            .waitTimeSeconds(LONG_POLL_WAIT_SECONDS)
            .build();
        return sqsClient.receiveMessage(request).messages();
    }

    private void handle(Message message) {
        try {
            if (processor.handle(message.body())) {
                delete(message);
            }
        } catch (RuntimeException e) {
            // Left on the queue: visibility timeout expires and SQS
            // redelivers, eventually landing in the DLQ after 5 receives.
            log.warn("Failed to process SES event message {}; leaving for redelivery", message.messageId(), e);
        }
    }

    private void delete(Message message) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
            .queueUrl(queueUrl)
            .receiptHandle(message.receiptHandle())
            .build());
    }

    private static String requireQueueUrl(MailProperties properties) {
        var queueUrl = properties.sesEventsQueueUrl();
        if (queueUrl == null || queueUrl.isBlank()) {
            throw new IllegalStateException(
                "alexandrea.mail.ses-events-queue-url is required in prod");
        }
        return queueUrl;
    }
}
