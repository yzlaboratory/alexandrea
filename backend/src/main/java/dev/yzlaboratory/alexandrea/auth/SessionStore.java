package dev.yzlaboratory.alexandrea.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SessionStore {

    private final JdbcClient jdbcClient;

    public SessionStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void invalidateAll(long userId) {
        jdbcClient
            .sql("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = :principalName")
            .param("principalName", AuthenticatedUser.principalName(userId))
            .update();
    }

    /** Leaves {@code currentSessionId} live so the caller who just proved their identity isn't signed out too. */
    public void invalidateAllExcept(long userId, String currentSessionId) {
        jdbcClient
            .sql("""
                DELETE FROM SPRING_SESSION
                WHERE PRINCIPAL_NAME = :principalName AND SESSION_ID != :currentSessionId
                """)
            .param("principalName", AuthenticatedUser.principalName(userId))
            .param("currentSessionId", currentSessionId)
            .update();
    }
}
