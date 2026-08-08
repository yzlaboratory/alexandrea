package dev.yzlaboratory.alexandrea.auth;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class SessionStore {

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public SessionStore(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    /**
     * Spring Session's JDBC repository always writes in its own {@code REQUIRES_NEW}
     * transaction, which would contend for SQLite's single-writer lock against a
     * caller's still-open transaction — deferring to the caller's commit (if any)
     * avoids that self-contention.
     */
    public void invalidateAllAfterCommit(long userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidateAll(userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidateAll(userId);
            }
        });
    }

    private void invalidateAll(long userId) {
        sessions.findByPrincipalName(AuthenticatedUser.principalName(userId))
            .keySet()
            .forEach(sessions::deleteById);
    }
}
