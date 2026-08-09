package dev.yzlaboratory.alexandrea.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yzlaboratory.alexandrea.auth.MigratedSqlite;
import dev.yzlaboratory.alexandrea.auth.MutableClock;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProcessedSesEventStoreTest {

    private MigratedSqlite db;
    private ProcessedSesEventStore store;

    @BeforeEach
    void setUp() {
        db = MigratedSqlite.create();
        store = new ProcessedSesEventStore(db.jdbcClient(), new MutableClock(Instant.parse("2026-06-07T12:00:00Z")));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void theFirstSightingOfAMessageAndRecipientPairIsNew() {
        assertThat(store.recordIfNew("ses-message-1", "recipient@example.com")).isTrue();
    }

    @Test
    void aReplayedMessageForTheSameRecipientIsNotNew() {
        store.recordIfNew("ses-message-1", "recipient@example.com");

        assertThat(store.recordIfNew("ses-message-1", "recipient@example.com")).isFalse();
    }

    @Test
    void theSameMessageIdCoveringADifferentRecipientIsStillNew() {
        store.recordIfNew("ses-message-1", "first@example.com");

        assertThat(store.recordIfNew("ses-message-1", "second@example.com")).isTrue();
    }

    @Test
    void theSameRecipientUnderADifferentMessageIdIsStillNew() {
        store.recordIfNew("ses-message-1", "recipient@example.com");

        assertThat(store.recordIfNew("ses-message-2", "recipient@example.com")).isTrue();
    }
}
