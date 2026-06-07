package dev.yzlaboratory.alexandrea.auth;

import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Thin JDBC repository over the {@code users} table (ADR 0014: plain JDBC via
 * {@link JdbcClient}, not JPA).
 *
 * <p>Email is the natural key and is normalised to lower case here so that
 * uniqueness and look-ups are case-insensitive without a SQLite collation —
 * every caller goes through this store, so the normalisation has a single home.
 * The store knows nothing about hashing or tokens; it persists what it is given.
 */
@Repository
public class UserStore {

    private final JdbcClient jdbcClient;
    private final Clock clock;

    public UserStore(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    public Optional<User> findByEmail(String email) {
        return jdbcClient
            .sql("SELECT * FROM users WHERE email = :email")
            .param("email", normalise(email))
            .query(UserStore::mapUser)
            .optional();
    }

    public Optional<User> findById(long id) {
        return jdbcClient
            .sql("SELECT * FROM users WHERE id = :id")
            .param("id", id)
            .query(UserStore::mapUser)
            .optional();
    }

    /**
     * Inserts a fresh, unverified account and returns its generated id. The
     * caller supplies an already-Argon2id-hashed password — the store never
     * sees plaintext.
     */
    public long createUnverified(String email, String passwordHash) {
        var now = clock.instant().toString();
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcClient
            .sql("""
                INSERT INTO users (email, password_hash, verified, created_at, updated_at)
                VALUES (:email, :passwordHash, 0, :now, :now)
                """)
            .param("email", normalise(email))
            .param("passwordHash", passwordHash)
            .param("now", now)
            .update(keyHolder);
        var key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Insert of user did not return a generated id");
        }
        return key.longValue();
    }

    /** Flips an account to verified. Idempotent: re-verifying is a no-op match. */
    public void markVerified(long userId) {
        jdbcClient
            .sql("UPDATE users SET verified = 1, updated_at = :now WHERE id = :id")
            .param("now", clock.instant().toString())
            .param("id", userId)
            .update();
    }

    private static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static User mapUser(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new User(
            rs.getLong("id"),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getInt("verified") == 1,
            Optional.ofNullable(rs.getString("last_media_type")),
            java.time.Instant.parse(rs.getString("created_at")),
            java.time.Instant.parse(rs.getString("updated_at"))
        );
    }
}
