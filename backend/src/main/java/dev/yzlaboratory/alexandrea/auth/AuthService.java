package dev.yzlaboratory.alexandrea.auth;

import dev.yzlaboratory.alexandrea.auth.mail.MailDispatcher;
import dev.yzlaboratory.alexandrea.auth.mail.VerificationMail;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    // A real {argon2} hash of an address no account will ever have, so
    // passwordEncoder.matches() runs its full cost on an unknown email too —
    // otherwise a fast "no such user" short-circuit would let response timing
    // reveal whether the address is registered, the same oracle signup closes.
    private static final String DUMMY_HASH =
        "{argon2}$argon2id$v=19$m=16384,t=2,p=1$RV/b40k462pPnnpoFcGzTw$i5PUH969l9692gVwVdgZkUStX0cjzfUtuZepeJ/l50U";

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

    @Transactional
    public void signup(String email, String rawPassword) {
        if (!PasswordPolicy.isAcceptable(rawPassword)) {
            throw new PasswordPolicyViolationException();
        }
        // Hash before the existence check so a duplicate signup and a fresh one
        // both pay Argon2id's deliberately-slow cost; otherwise response time
        // reveals whether the address is already registered.
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
            // catches when not racing, so it answers identically.
            throw new EmailAlreadyRegisteredException();
        }
        var verificationLink = issueVerificationLink(userId);
        mailDispatcher.dispatch(VerificationMail.build(email, verificationLink));
    }

    @Transactional
    public void resendVerification(String email) {
        var user = userStore.findByEmail(email).orElse(null);
        if (user == null || user.verified()) {
            return;
        }
        var verificationLink = issueVerificationLink(user.id());
        mailDispatcher.dispatch(VerificationMail.build(email, verificationLink));
    }

    public LoginOutcome login(String email, String rawPassword) {
        // A registered password always satisfies PasswordPolicy (signup enforces
        // it), so an out-of-policy submission is already a guaranteed mismatch.
        // Rejecting it here, rather than falling through to the hash below,
        // skips the full-cost Argon2id run — the same denial-of-service cost
        // PasswordPolicy's own Javadoc explains it guards against — on every
        // anonymous request, known email or not.
        if (!PasswordPolicy.isAcceptable(rawPassword)) {
            return new LoginOutcome.InvalidCredentials();
        }
        var user = userStore.findByEmail(email);
        var hashToCheck = user.map(User::passwordHash).orElse(DUMMY_HASH);
        var passwordMatches = passwordEncoder.matches(rawPassword, hashToCheck);
        if (user.isEmpty() || !passwordMatches) {
            return new LoginOutcome.InvalidCredentials();
        }
        var authenticatedUser = user.get();
        if (!authenticatedUser.verified()) {
            return new LoginOutcome.UnverifiedAccount();
        }
        return new LoginOutcome.Authenticated(authenticatedUser);
    }

    /**
     * A session can only resolve to a row created by {@link #login}, and v1 has
     * no account-deletion path (ADR 0021) to remove one afterward — a miss here
     * is an invariant violation, not a routine user-facing error.
     */
    public User requireUser(long userId) {
        return userStore.findById(userId)
            .orElseThrow(() -> new IllegalStateException(
                "Session principal has no matching user row: " + userId));
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
