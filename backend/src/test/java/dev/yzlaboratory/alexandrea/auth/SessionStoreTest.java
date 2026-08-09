package dev.yzlaboratory.alexandrea.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionStoreTest {

    private MigratedSqlite db;
    private SessionStore sessionStore;

    @BeforeEach
    void setUp() {
        db = MigratedSqlite.create();
        sessionStore = new SessionStore(db.jdbcClient());
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void invalidateAllExceptDeletesTheUsersOtherSessionsButKeepsTheCurrentOne() {
        insertSession("session-a", 1L);
        insertSession("session-b", 1L);
        insertSession("session-c", 1L);

        sessionStore.invalidateAllExcept(1L, "session-b");

        assertThat(sessionIds()).containsExactly("session-b");
    }

    @Test
    void invalidateAllExceptLeavesAnotherUsersSessionsUntouched() {
        insertSession("mine", 1L);
        insertSession("theirs", 2L);

        sessionStore.invalidateAllExcept(1L, "mine");

        assertThat(sessionIds()).containsExactlyInAnyOrder("mine", "theirs");
    }

    @Test
    void invalidateAllExceptIsANoOpWhenTheUserHasNoOtherSessions() {
        insertSession("only-session", 1L);

        sessionStore.invalidateAllExcept(1L, "only-session");

        assertThat(sessionIds()).containsExactly("only-session");
    }

    private void insertSession(String sessionId, long userId) {
        db.jdbcClient()
            .sql("""
                INSERT INTO SPRING_SESSION
                    (PRIMARY_ID, SESSION_ID, CREATION_TIME, LAST_ACCESS_TIME, MAX_INACTIVE_INTERVAL, EXPIRY_TIME, PRINCIPAL_NAME)
                VALUES (:primaryId, :sessionId, 0, 0, 1800, 0, :principalName)
                """)
            .param("primaryId", sessionId + "-primary")
            .param("sessionId", sessionId)
            .param("principalName", AuthenticatedUser.principalName(userId))
            .update();
    }

    private List<String> sessionIds() {
        return db.jdbcClient().sql("SELECT SESSION_ID FROM SPRING_SESSION").query(String.class).list();
    }
}
