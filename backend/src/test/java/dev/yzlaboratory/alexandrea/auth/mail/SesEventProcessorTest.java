package dev.yzlaboratory.alexandrea.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yzlaboratory.alexandrea.auth.MigratedSqlite;
import dev.yzlaboratory.alexandrea.auth.MutableClock;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exercises the parsing/idempotency/suppression logic directly against
 * fixture JSON strings — no SQS, no mocked poll loop.
 */
class SesEventProcessorTest {

    private static final String RECIPIENT = "recipient@example.com";
    private static final String MESSAGE_ID = "ses-message-1";

    private final ObjectMapper mapper = new ObjectMapper();

    private MigratedSqlite db;
    private UnsendableAddressStore unsendableAddressStore;
    private SesEventProcessor processor;

    @BeforeEach
    void setUp() {
        db = MigratedSqlite.create();
        var clock = new MutableClock(Instant.parse("2026-06-07T12:00:00Z"));
        unsendableAddressStore = new UnsendableAddressStore(db.jdbcClient(), clock);
        var processedEventStore = new ProcessedSesEventStore(db.jdbcClient(), clock);
        processor = new SesEventProcessor(mapper, processedEventStore, unsendableAddressStore);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void aPermanentBounceMarksTheRecipientUnsendableAndIsAcked() {
        var body = snsBody(bounceEvent("Permanent", RECIPIENT));

        assertThat(processor.handle(body)).isTrue();

        assertThat(unsendableAddressStore.isUnsendable(RECIPIENT)).isTrue();
    }

    @Test
    void aTransientBounceIsObservedOnlyAndDoesNotSuppress() {
        var body = snsBody(bounceEvent("Transient", RECIPIENT));

        assertThat(processor.handle(body)).isTrue();

        assertThat(unsendableAddressStore.isUnsendable(RECIPIENT)).isFalse();
    }

    @Test
    void anUndeterminedBounceIsObservedOnlyAndDoesNotSuppress() {
        var body = snsBody(bounceEvent("Undetermined", RECIPIENT));

        assertThat(processor.handle(body)).isTrue();

        assertThat(unsendableAddressStore.isUnsendable(RECIPIENT)).isFalse();
    }

    @Test
    void aComplaintMarksTheRecipientUnsendable() {
        var body = snsBody(complaintEvent(RECIPIENT));

        assertThat(processor.handle(body)).isTrue();

        assertThat(unsendableAddressStore.isUnsendable(RECIPIENT)).isTrue();
    }

    @Test
    void aComplaintWithMultipleComplainedRecipientsSuppressesAllOfThem() {
        var body = snsBody(complaintEvent("first@example.com", "second@example.com"));

        assertThat(processor.handle(body)).isTrue();

        assertThat(unsendableAddressStore.isUnsendable("first@example.com")).isTrue();
        assertThat(unsendableAddressStore.isUnsendable("second@example.com")).isTrue();
    }

    @Test
    void aRejectEventIsObservedOnlyAndAcked() {
        var body = snsBody(observeOnlyEvent("Reject"));

        assertThat(processor.handle(body)).isTrue();
    }

    @Test
    void aRenderingFailureEventIsObservedOnlyAndAcked() {
        var body = snsBody(observeOnlyEvent("Rendering Failure"));

        assertThat(processor.handle(body)).isTrue();
    }

    @Test
    void aDeliveryDelayEventIsObservedOnlyAndAcked() {
        var body = snsBody(observeOnlyEvent("DeliveryDelay"));

        assertThat(processor.handle(body)).isTrue();
    }

    @Test
    void anUnrecognisedEventTypeIsLoggedAndAckedRatherThanRejected() {
        var body = snsBody(observeOnlyEvent("SomeFutureEventType"));

        assertThat(processor.handle(body)).isTrue();
    }

    @Test
    void aBodyThatIsNotJsonIsNotAcked() {
        assertThat(processor.handle("this is not json")).isFalse();
    }

    @Test
    void anSnsEnvelopeWhoseMessageFieldIsNotJsonIsNotAcked() {
        var envelope = mapper.createObjectNode();
        envelope.put("Type", "Notification");
        envelope.put("Message", "also not json");

        assertThat(processor.handle(writeJson(envelope))).isFalse();
    }

    @Test
    void anSesEventMissingEventTypeIsNotAcked() {
        var sesEvent = mapper.createObjectNode();
        sesEvent.putObject("mail").put("messageId", MESSAGE_ID);

        assertThat(processor.handle(snsBody(writeJson(sesEvent)))).isFalse();
    }

    @Test
    void anSesEventMissingTheMailMessageIdIsNotAcked() {
        var sesEvent = mapper.createObjectNode();
        sesEvent.put("eventType", "Bounce");

        assertThat(processor.handle(snsBody(writeJson(sesEvent)))).isFalse();
    }

    @Test
    void aReplayedBounceMessageHasNoDoubleEffect() {
        var body = snsBody(bounceEvent("Permanent", RECIPIENT));

        assertThat(processor.handle(body)).isTrue();
        assertThat(processor.handle(body)).isTrue();

        assertThat(unsendableAddressStore.isUnsendable(RECIPIENT)).isTrue();
        assertThat(processedEventRowCount(MESSAGE_ID, RECIPIENT)).isOne();
    }

    @Test
    void theSameMessageBouncingAndThenSeparatelyComplainingActsOnBoth() {
        var bounceBody = snsBody(bounceEvent("Permanent", RECIPIENT));
        var complaintBody = snsBody(complaintEvent(RECIPIENT));

        assertThat(processor.handle(bounceBody)).isTrue();
        assertThat(processor.handle(complaintBody)).isTrue();

        assertThat(unsendableAddressStore.isUnsendable(RECIPIENT)).isTrue();
        assertThat(processedEventRowCount(MESSAGE_ID, RECIPIENT)).isOne();
    }

    private int processedEventRowCount(String messageId, String recipient) {
        return db.jdbcClient()
            .sql("SELECT COUNT(*) FROM processed_ses_events WHERE message_id = :messageId AND recipient = :recipient")
            .param("messageId", messageId)
            .param("recipient", recipient)
            .query(Integer.class)
            .single();
    }

    private String snsBody(String sesEventJson) {
        var envelope = mapper.createObjectNode();
        envelope.put("Type", "Notification");
        envelope.put("MessageId", "sns-notification-id");
        envelope.put("TopicArn", "arn:aws:sns:eu-central-1:123456789012:alexandrea-prod-ses-events");
        envelope.put("Message", sesEventJson);
        envelope.put("Timestamp", "2026-06-07T12:00:01.000Z");
        return writeJson(envelope);
    }

    private String bounceEvent(String bounceType, String... recipients) {
        var sesEvent = mapper.createObjectNode();
        sesEvent.put("eventType", "Bounce");
        var bounce = sesEvent.putObject("bounce");
        bounce.put("bounceType", bounceType);
        var bouncedRecipients = bounce.putArray("bouncedRecipients");
        for (var recipient : recipients) {
            bouncedRecipients.addObject().put("emailAddress", recipient);
        }
        sesEvent.putObject("mail").put("messageId", MESSAGE_ID);
        return writeJson(sesEvent);
    }

    private String complaintEvent(String... recipients) {
        var sesEvent = mapper.createObjectNode();
        sesEvent.put("eventType", "Complaint");
        var complaint = sesEvent.putObject("complaint");
        var complainedRecipients = complaint.putArray("complainedRecipients");
        for (var recipient : recipients) {
            complainedRecipients.addObject().put("emailAddress", recipient);
        }
        sesEvent.putObject("mail").put("messageId", MESSAGE_ID);
        return writeJson(sesEvent);
    }

    private String observeOnlyEvent(String eventType) {
        var sesEvent = mapper.createObjectNode();
        sesEvent.put("eventType", eventType);
        sesEvent.putObject("mail").put("messageId", MESSAGE_ID);
        return writeJson(sesEvent);
    }

    private String writeJson(ObjectNode node) {
        return mapper.writeValueAsString(node);
    }
}
