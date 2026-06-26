package dev.yzlaboratory.alexandrea.auth;

import dev.yzlaboratory.alexandrea.auth.mail.MailDispatcher;
import dev.yzlaboratory.alexandrea.auth.mail.VerificationMail;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signup → verify use-case layer: it owns the ordering rules the individual
 * collaborators must not know. Two of those rules are load-bearing:
 *
 * <ul>
 *   <li><b>Mail is handed to {@link MailDispatcher} after the state commits.</b>
 *       The account and its verification token are committed first; the link is
 *       then dispatched off the request thread, so a send failure leaves a usable
 *       account on the resend state and response latency never reveals whether
 *       mail was sent (ADR 0024).</li>
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

    private final UserStore userStore;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final MailDispatcher mailDispatcher;
    private final AuthProperties properties;

    public AuthService(
        UserStore userStore,
        TokenService tokenService,
        PasswordEncoder passwordEncoder,
        MailDispatcher mailDispatcher,
        AuthProperties properties
    ) {
        this.userStore = userStore;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.mailDispatcher = mailDispatcher;
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
        mailDispatcher.dispatch(VerificationMail.build(email, verificationLink));
    }

    /** Unknown or already-verified addresses are a silent no-op, so the response cannot probe account state (ADR 0024). */
    @Transactional
    public void resendVerification(String email) {
        var user = userStore.findByEmail(email).orElse(null);
        if (user == null || user.verified()) {
            return;
        }
        try {
            var verificationLink = issueVerificationLink(user.id());
            mailDispatcher.dispatch(VerificationMail.build(email, verificationLink));
        } catch (DataIntegrityViolationException raced) {
            // A concurrent resend already took the single active-token slot and is
            // mailing its link; converge on the same 202 (ADR 0024) rather than
            // surfacing the collision as a 500.
        }
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
}
