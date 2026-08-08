package dev.yzlaboratory.alexandrea.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Email is normalised to lower case here, so the normalisation has a single home
 * every caller passes through.
 */
@Repository
public class UserStore {

    private static final String EMAIL_COLUMN = "email";

    private final JdbcClient jdbcClient;
    private final Clock clock;

    public UserStore(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    public Optional<User> findByEmail(String email) {
        return jdbcClient
            .sql("SELECT * FROM users WHERE email = :email")
            .param(EMAIL_COLUMN, normalise(email))
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

    /** The caller supplies an already-Argon2id-hashed password — the store never sees plaintext. */
    public long createUnverified(String email, String passwordHash) {
        var now = clock.instant().toString();
        var keyHolder = new GeneratedKeyHolder();
        jdbcClient
            .sql("""
                INSERT INTO users (email, password_hash, verified, created_at, updated_at)
                VALUES (:email, :passwordHash, 0, :now, :now)
                """)
            .param(EMAIL_COLUMN, normalise(email))
            .param("passwordHash", passwordHash)
            .param("now", now)
            .update(keyHolder);
        var key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Insert of user did not return a generated id");
        }
        return key.longValue();
    }

    public void markVerified(long userId) {
        jdbcClient
            .sql("UPDATE users SET verified = 1, updated_at = :now WHERE id = :id")
            .param("now", clock.instant().toString())
            .param("id", userId)
            .update();
    }

    /** The caller supplies an already-Argon2id-hashed password — the store never sees plaintext. */
    public void updatePasswordHash(long userId, String passwordHash) {
        jdbcClient
            .sql("UPDATE users SET password_hash = :passwordHash, updated_at = :now WHERE id = :id")
            .param("passwordHash", passwordHash)
            .param("now", clock.instant().toString())
            .param("id", userId)
            .update();
    }

    private static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static User mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new User(
            rs.getLong("id"),
            rs.getString(EMAIL_COLUMN),
            rs.getString("password_hash"),
            rs.getInt("verified") == 1,
            rs.getString("last_media_type"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
        );
    }
}
