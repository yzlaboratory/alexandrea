package dev.yzlaboratory.alexandrea.auth.mail;

import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Sends mail after the surrounding transaction commits and off the request
 * thread. Three properties are load-bearing:
 *
 * <ul>
 *   <li><b>After commit</b> — the recipient never gets a link for an account that
 *       was rolled back.</li>
 *   <li><b>Off the request thread</b> — response latency cannot reveal whether
 *       mail was sent, so a slow provider does not turn the response time into an
 *       account-enumeration oracle (ADR 0024). This is the reason the send is not
 *       inlined into the request.</li>
 *   <li><b>Best-effort</b> — a send failure is logged and swallowed: the account
 *       exists and the user can resend, so a mail outage must not fail the
 *       request.</li>
 * </ul>
 *
 * <p>Outside a transaction (a direct call with no synchronization active) it
 * dispatches immediately, still off-thread.
 */
@Component
public class MailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MailDispatcher.class);

    private final MailSender mailSender;
    private final Executor executor;

    public MailDispatcher(
        MailSender mailSender,
        @Qualifier("verificationMailExecutor") Executor executor
    ) {
        this.mailSender = mailSender;
        this.executor = executor;
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
        try {
            mailSender.send(message);
        } catch (RuntimeException e) {
            log.warn("Verification email to {} failed to send; user can resend", message.to(), e);
        }
    }
}
