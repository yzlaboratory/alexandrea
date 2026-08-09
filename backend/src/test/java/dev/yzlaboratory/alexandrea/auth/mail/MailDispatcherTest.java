package dev.yzlaboratory.alexandrea.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yzlaboratory.alexandrea.auth.MigratedSqlite;
import dev.yzlaboratory.alexandrea.auth.MutableClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The suppression check {@link MailDispatcher} owns as the single choke-point
 * every outgoing mail funnels through — exercised with a synchronous executor
 * so dispatch and delivery happen on the calling thread.
 */
class MailDispatcherTest {

    private MigratedSqlite db;
    private RecordingMailSender mailSender;
    private MailDispatcher dispatcher;
    private UnsendableAddressStore unsendableAddressStore;

    @BeforeEach
    void setUp() {
        db = MigratedSqlite.create();
        var clock = new MutableClock(Instant.parse("2026-06-07T12:00:00Z"));
        unsendableAddressStore = new UnsendableAddressStore(db.jdbcClient(), clock);
        mailSender = new RecordingMailSender();
        dispatcher = new MailDispatcher(mailSender, Runnable::run, unsendableAddressStore);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void aMessageToAnUntrackedAddressIsSent() {
        dispatcher.dispatch(new MailMessage("nobody-flagged@example.com", "Subject", "Body"));

        assertThat(mailSender.sent).hasSize(1);
    }

    @Test
    void aMessageToASuppressedAddressIsSkipped() {
        unsendableAddressStore.markUnsendable("bounced@example.com", UnsendableReason.BOUNCE);

        dispatcher.dispatch(new MailMessage("bounced@example.com", "Subject", "Body"));

        assertThat(mailSender.sent).isEmpty();
    }

    @Test
    void suppressionIsCaseInsensitiveMatchingTheRestOfIdentity() {
        unsendableAddressStore.markUnsendable("Bounced@Example.com", UnsendableReason.COMPLAINT);

        dispatcher.dispatch(new MailMessage("bounced@example.com", "Subject", "Body"));

        assertThat(mailSender.sent).isEmpty();
    }

    @Test
    void onlyTheSuppressedRecipientIsSkippedAmongMultipleDispatches() {
        unsendableAddressStore.markUnsendable("suppressed@example.com", UnsendableReason.BOUNCE);

        dispatcher.dispatch(new MailMessage("suppressed@example.com", "Subject", "Body"));
        dispatcher.dispatch(new MailMessage("fine@example.com", "Subject", "Body"));

        assertThat(mailSender.sent).extracting(MailMessage::to).containsExactly("fine@example.com");
    }

    private static class RecordingMailSender implements MailSender {
        final List<MailMessage> sent = new ArrayList<>();

        @Override
        public void send(MailMessage message) {
            sent.add(message);
        }
    }
}
