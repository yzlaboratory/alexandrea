package dev.yzlaboratory.alexandrea.auth.mail;

import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Dispatches after commit so the recipient never gets a link for an account that
 * was rolled back, and best-effort so a mail-provider outage fails the dispatch,
 * not the request — the account exists and the user can resend.
 */
@Component
public class MailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MailDispatcher.class);

    private final MailSender mailSender;
    private final Executor executor;
    private final UnsendableAddressStore unsendableAddressStore;

    public MailDispatcher(
        MailSender mailSender,
        @Qualifier("verificationMailExecutor") Executor executor,
        UnsendableAddressStore unsendableAddressStore
    ) {
        this.mailSender = mailSender;
        this.executor = executor;
        this.unsendableAddressStore = unsendableAddressStore;
    }

    public void dispatch(MailMessage message) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            submit(message);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(message);
            }
        });
    }

    private void submit(MailMessage message) {
        executor.execute(() -> sendBestEffort(message));
    }

    private void sendBestEffort(MailMessage message) {
        if (unsendableAddressStore.isUnsendable(message.to())) {
            log.info("Skipping mail to {}: address is marked unsendable", message.to());
            return;
        }
        try {
            mailSender.send(message);
        } catch (RuntimeException e) {
            log.warn("Verification email to {} failed to send; user can resend", message.to(), e);
        }
    }
}
