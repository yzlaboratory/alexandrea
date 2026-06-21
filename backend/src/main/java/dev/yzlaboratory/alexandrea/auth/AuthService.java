package dev.yzlaboratory.alexandrea.auth;

import dev.yzlaboratory.alexandrea.auth.mail.MailMessage;
import dev.yzlaboratory.alexandrea.auth.mail.MailSender;
import dev.yzlaboratory.alexandrea.auth.mail.VerificationMail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The signup → verify use-case layer: it owns the ordering rules the individual
 * collaborators must not know. Two of those rules are load-bearing:
 *
 * <ul>
 *   <li><b>Mail is dispatched best-effort, after the state commits.</b> The
 *       account and its verification token are committed first; only then is the
 *       link mailed, in the same request thread. A mail-send failure therefore
 *       leaves a usable account on the "check your email / resend" state rather
 *       than rolling the signup back — resend exists precisely for that case.</li>
 *   <li><b>The plaintext password reaches only the encoder.</b> The store
 *       persists the Argon2id hash; nothing else sees the raw value.</li>
 * </ul>
 *
 * <p>The verified/unverified re-signup branch of ADR 0024 (overwrite an
 * unclaimed account and re-send) is out of scope for this tracer bullet; here a
 * duplicate is simply rejected. What this slice does honour is the
 * enumeration-safety invariant: signup costs the same and answers the same
 * whether or not the address is already registered.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserStore userStore;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final MailSender mailSender;
    private final AuthProperties properties;

    public AuthService(
        UserStore userStore,
        TokenService tokenService,
        PasswordEncoder passwordEncoder,
        MailSender mailSender,
        AuthProperties properties
    ) {
        this.userStore = userStore;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSender;
        this.properties = properties;
    }

    /** A duplicate email surfaces as {@link EmailAlreadyRegisteredException}, swallowed by the web layer. */
    @Transactional
    public void signup(String email, String rawPassword) {
        if (!PasswordPolicy.isAcceptable(rawPassword)) {
            throw new PasswordPolicyViolationException();
        }
        // Hash before the existence check so a duplicate signup and a fresh one
        // both pay Argon2id's deliberately-slow cost — response time must not
        // reveal whether the address is already registered (ADR 0024).
        var passwordHash = passwordEncoder.encode(rawPassword);
        if (userStore.findByEmail(email).isPresent()) {
            throw new EmailAlreadyRegisteredException();
        }

        long userId;
        try {
            userId = userStore.createUnverified(email, passwordHash);
        } catch (DataIntegrityViolationException raced) {
            // Two concurrent signups for the same new address: the unique-email
            // index rejects the loser. It is the same duplicate the check above
            // catches when not racing, so it answers identically (ADR 0024).
            throw new EmailAlreadyRegisteredException();
        }
        var verificationLink = issueVerificationLink(userId);
        dispatchAfterCommit(VerificationMail.build(email, verificationLink));
    }

    /** Unknown or already-verified addresses are a silent no-op, so the response cannot probe account state (ADR 0024). */
    @Transactional
    public void resendVerification(String email) {
        var user = userStore.findByEmail(email).orElse(null);
        if (user == null || user.verified()) {
            return;
        }
        var verificationLink = issueVerificationLink(user.id());
        dispatchAfterCommit(VerificationMail.build(email, verificationLink));
    }

    @Transactional
    public TokenConsumption verify(String rawToken) {
        var outcome = tokenService.consume(TokenKind.VERIFICATION, rawToken);
        if (outcome instanceof TokenConsumption.Consumed consumed) {
            userStore.markVerified(consumed.userId());
        }
        return outcome;
    }

    private String issueVerificationLink(long userId) {
        var rawToken = tokenService.issue(TokenKind.VERIFICATION, userId);
        return properties.verificationUrlTemplate().replace("{token}", rawToken);
    }

    /**
     * Sends the message only once the surrounding transaction has committed, so
     * the recipient never gets a link for an account that was rolled back; a
     * send failure is logged and swallowed, leaving the user on the resend
     * state. Outside a transaction (e.g. a unit test calling directly) it sends
     * inline.
     */
    private void dispatchAfterCommit(MailMessage message) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sendBestEffort(message);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sendBestEffort(message);
            }
        });
    }

    private void sendBestEffort(MailMessage message) {
        try {
            mailSender.send(message);
        } catch (RuntimeException e) {
            // Best-effort: the account exists and can be re-mailed via resend, so
            // a transient mail outage must not turn signup into an error.
            log.warn("Verification email to {} failed to send; user can resend", message.to(), e);
        }
    }
}
