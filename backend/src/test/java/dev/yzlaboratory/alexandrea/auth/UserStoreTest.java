package dev.yzlaboratory.alexandrea.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserStoreTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-06-07T12:00:00Z"), ZoneOffset.UTC);

    private MigratedSqlite db;
    private UserStore userStore;

    @BeforeEach
    void setUp() {
        db = MigratedSqlite.create();
        userStore = new UserStore(db.jdbcClient(), FIXED);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void createsAnUnverifiedAccountWithNoStickyMediaType() {
        var id = userStore.createUnverified("reader@example.com", "{argon2}hash");

        var user = userStore.findById(id).orElseThrow();
        assertThat(user.verified()).isFalse();
        assertThat(user.passwordHash()).isEqualTo("{argon2}hash");
        assertThat(user.lastMediaType()).isNull();
    }

    @Test
    void emailLookupIsCaseAndWhitespaceInsensitive() {
        userStore.createUnverified("Reader@Example.com", "{argon2}hash");

        assertThat(userStore.findByEmail("  reader@example.com  ")).isPresent();
    }

    @Test
    void markVerifiedFlipsTheFlag() {
        var id = userStore.createUnverified("reader@example.com", "{argon2}hash");

        userStore.markVerified(id);

        assertThat(userStore.findById(id).orElseThrow().verified()).isTrue();
    }

    @Test
    void absentEmailReturnsEmpty() {
        assertThat(userStore.findByEmail("nobody@example.com")).isEmpty();
    }

    @Test
    void updatePasswordHashReplacesTheStoredHash() {
        var id = userStore.createUnverified("reader@example.com", "{argon2}old-hash");

        userStore.updatePasswordHash(id, "{argon2}new-hash");

        assertThat(userStore.findById(id).orElseThrow().passwordHash()).isEqualTo("{argon2}new-hash");
    }

    @Test
    void updateEmailReplacesTheStoredEmailNormalised() {
        var id = userStore.createUnverified("reader@example.com", "{argon2}hash");

        userStore.updateEmail(id, "  New-Reader@Example.com  ");

        assertThat(userStore.findById(id).orElseThrow().email()).isEqualTo("new-reader@example.com");
    }

    @Test
    void updateLastMediaTypeStoresTheChosenType() {
        var id = userStore.createUnverified("reader@example.com", "{argon2}hash");

        userStore.updateLastMediaType(id, "tv");

        assertThat(userStore.findById(id).orElseThrow().lastMediaType()).isEqualTo("tv");
    }

    @Test
    void updateLastMediaTypeCanReplaceAPreviousChoice() {
        var id = userStore.createUnverified("reader@example.com", "{argon2}hash");
        userStore.updateLastMediaType(id, "tv");

        userStore.updateLastMediaType(id, "books");

        assertThat(userStore.findById(id).orElseThrow().lastMediaType()).isEqualTo("books");
    }
}
