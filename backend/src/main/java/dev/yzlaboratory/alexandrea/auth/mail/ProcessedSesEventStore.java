package dev.yzlaboratory.alexandrea.auth.mail;

import java.time.Clock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProcessedSesEventStore {

    private final JdbcClient jdbcClient;
    private final Clock clock;

    public ProcessedSesEventStore(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    /**
     * True the first time this (messageId, recipient) pair is recorded; false
     * on a replay — SQS's at-least-once delivery — so the caller can skip
     * acting again while the message is still safe to delete as handled.
     */
    public boolean recordIfNew(String messageId, String recipient) {
        var inserted = jdbcClient
            .sql("""
                INSERT INTO processed_ses_events (message_id, recipient, processed_at)
                VALUES (:messageId, :recipient, :now)
                ON CONFLICT(message_id, recipient) DO NOTHING
                """)
            .param("messageId", messageId)
            .param("recipient", recipient)
            .param("now", clock.instant().toString())
            .update();
        return inserted > 0;
    }
}
