package dev.yzlaboratory.alexandrea.auth.mail;

import java.util.Objects;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The parsing/idempotency/suppression logic behind {@link SesEventsConsumer},
 * kept free of any AWS SDK type so it's testable against fixture JSON strings
 * without a mocked poll loop.
 */
@Component
public class SesEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(SesEventProcessor.class);

    private final ObjectMapper objectMapper;
    private final ProcessedSesEventStore processedEventStore;
    private final UnsendableAddressStore unsendableAddressStore;

    public SesEventProcessor(
        ObjectMapper objectMapper,
        ProcessedSesEventStore processedEventStore,
        UnsendableAddressStore unsendableAddressStore
    ) {
        this.objectMapper = objectMapper;
        this.processedEventStore = processedEventStore;
        this.unsendableAddressStore = unsendableAddressStore;
    }

    /**
     * True when the message was handled — including a no-op duplicate or an
     * unrecognised/observe-only event type — and is safe to delete from the
     * queue. False only when the body couldn't be parsed as an SNS-wrapped SES
     * event, so the caller leaves it for SQS to redeliver rather than losing
     * it. Transactional as a whole so a crash between recording an event as
     * processed and actually suppressing its recipient can't happen — that
     * gap would make a redelivery silently skip the suppression it never did.
     */
    @Transactional
    public boolean handle(String rawMessageBody) {
        var sesEvent = parseSesEvent(rawMessageBody);
        if (sesEvent == null) {
            return false;
        }
        var eventType = sesEvent.path("eventType").asString(null);
        var messageId = sesEvent.path("mail").path("messageId").asString(null);
        if (eventType == null || messageId == null) {
            log.warn("SES event is missing eventType or mail.messageId; discarding: {}", rawMessageBody);
            return false;
        }

        // Reject/Rendering Failure/DeliveryDelay (SES's own event-notification
        // strings, not the config-API's upper-snake-case ones the Terraform
        // matching_event_types list uses) are observed only: SES already
        // retries or there's nothing to retry, and no recipient to suppress.
        // Anything else is a future event type this handler doesn't know
        // about yet — logged and acked rather than left stuck in the queue.
        switch (eventType) {
            case "Bounce" -> handleBounce(sesEvent, messageId);
            case "Complaint" -> handleComplaint(sesEvent, messageId);
            case "Reject", "Rendering Failure", "DeliveryDelay" ->
                log.info("Observed a {} event for SES message {}; no app action needed", eventType, messageId);
            default -> log.info("Ignoring unrecognised SES event type '{}' for message {}", eventType, messageId);
        }
        return true;
    }

    private JsonNode parseSesEvent(String rawMessageBody) {
        try {
            // SNS envelope delivery (raw off): the actual SES event is a JSON
            // string nested in the .Message field, not the top-level object.
            var envelope = objectMapper.readTree(rawMessageBody);
            return objectMapper.readTree(envelope.path("Message").asString(""));
        } catch (JacksonException e) {
            log.warn("Discarding malformed SES event queue message", e);
            return null;
        }
    }

    private void handleBounce(JsonNode sesEvent, String messageId) {
        var bounceType = sesEvent.path("bounce").path("bounceType").asString("");
        if (!"Permanent".equals(bounceType)) {
            log.info(
                "Observed a {} bounce for SES message {}; SES already retries non-permanent bounces itself",
                bounceType, messageId);
            return;
        }
        recipientsOf(sesEvent.path("bounce").path("bouncedRecipients"))
            .forEach(recipient -> suppress(messageId, recipient, UnsendableReason.BOUNCE));
    }

    private void handleComplaint(JsonNode sesEvent, String messageId) {
        recipientsOf(sesEvent.path("complaint").path("complainedRecipients"))
            .forEach(recipient -> suppress(messageId, recipient, UnsendableReason.COMPLAINT));
    }

    private void suppress(String messageId, String recipient, UnsendableReason reason) {
        if (processedEventStore.recordIfNew(messageId, recipient)) {
            unsendableAddressStore.markUnsendable(recipient, reason);
        }
    }

    private static Stream<String> recipientsOf(JsonNode recipients) {
        return StreamSupport.stream(recipients.spliterator(), false)
            .map(recipient -> recipient.path("emailAddress").asString(null))
            .filter(Objects::nonNull);
    }
}
