package dev.yzlaboratory.alexandrea.auth.mail;

import dev.yzlaboratory.alexandrea.auth.Emails;
import java.time.Clock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The suppression list {@link MailDispatcher} checks before every send. Once
 * an address is here it stays here — nothing in this feature un-suppresses
 * one, matching SES's own account-level suppression list, which is also
 * append-only from the app's perspective.
 */
@Repository
public class UnsendableAddressStore {

    private final JdbcClient jdbcClient;
    private final Clock clock;

    public UnsendableAddressStore(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    public boolean isUnsendable(String email) {
        return jdbcClient
            .sql("SELECT COUNT(*) FROM unsendable_addresses WHERE email = :email")
            .param("email", Emails.normalise(email))
            .query(Integer.class)
            .single() > 0;
    }

    /** Idempotent: marking an already-unsendable address again leaves its original {@code recordedAt} untouched. */
    public void markUnsendable(String email, UnsendableReason reason) {
        jdbcClient
            .sql("""
                INSERT INTO unsendable_addresses (email, reason, recorded_at)
                VALUES (:email, :reason, :now)
                ON CONFLICT(email) DO NOTHING
                """)
            .param("email", Emails.normalise(email))
            .param("reason", reason.name())
            .param("now", clock.instant().toString())
            .update();
    }
}
