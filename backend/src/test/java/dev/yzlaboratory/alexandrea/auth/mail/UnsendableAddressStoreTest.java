package dev.yzlaboratory.alexandrea.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yzlaboratory.alexandrea.auth.MigratedSqlite;
import dev.yzlaboratory.alexandrea.auth.MutableClock;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UnsendableAddressStoreTest {

    private MigratedSqlite db;
    private UnsendableAddressStore store;

    @BeforeEach
    void setUp() {
        db = MigratedSqlite.create();
        store = new UnsendableAddressStore(db.jdbcClient(), new MutableClock(Instant.parse("2026-06-07T12:00:00Z")));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void anAddressThatWasNeverMarkedIsSendable() {
        assertThat(store.isUnsendable("nobody@example.com")).isFalse();
    }

    @Test
    void aMarkedAddressIsUnsendable() {
        store.markUnsendable("bounced@example.com", UnsendableReason.BOUNCE);

        assertThat(store.isUnsendable("bounced@example.com")).isTrue();
    }

    @Test
    void theCheckIsCaseAndWhitespaceInsensitiveLikeEverywhereElseEmailIsAnIdentity() {
        store.markUnsendable("  Bounced@Example.com  ", UnsendableReason.COMPLAINT);

        assertThat(store.isUnsendable("bounced@example.com")).isTrue();
    }

    @Test
    void markingTheSameAddressTwiceIsANoOpNotAnError() {
        store.markUnsendable("repeat@example.com", UnsendableReason.BOUNCE);

        store.markUnsendable("repeat@example.com", UnsendableReason.COMPLAINT);

        assertThat(rowCountFor("repeat@example.com")).isOne();
    }

    @Test
    void markingOneAddressDoesNotAffectAnother() {
        store.markUnsendable("marked@example.com", UnsendableReason.BOUNCE);

        assertThat(store.isUnsendable("unrelated@example.com")).isFalse();
    }

    private int rowCountFor(String email) {
        return db.jdbcClient()
            .sql("SELECT COUNT(*) FROM unsendable_addresses WHERE email = :email")
            .param("email", email)
            .query(Integer.class)
            .single();
    }
}
