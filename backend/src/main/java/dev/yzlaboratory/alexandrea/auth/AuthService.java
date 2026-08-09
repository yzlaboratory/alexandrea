package dev.yzlaboratory.alexandrea.auth;

import dev.yzlaboratory.alexandrea.auth.mail.AlreadyRegisteredMail;
import dev.yzlaboratory.alexandrea.auth.mail.MailDispatcher;
import dev.yzlaboratory.alexandrea.auth.mail.PasswordResetMail;
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
    private final SessionStore sessionStore;
    private final PasswordEncoder passwordEncoder;
    private final MailDispatcher mailDispatcher;
    private final AuthProperties properties;
    private final RateLimiter rateLimiter;

    public AuthService(
        UserStore userStore,
        TokenService tokenService,
        SessionStore sessionStore,
        PasswordEncoder passwordEncoder,
        MailDispatcher mailDispatcher,
        AuthProperties properties,
        RateLimiter rateLimiter
    ) {
        this.userStore = userStore;
        this.tokenService = tokenService;
        this.sessionStore = sessionStore;
        this.passwordEncoder = passwordEncoder;
        this.mailDispatcher = mailDispatcher;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public void signup(String email, String rawPassword, String clientIp) {
        if (!rateLimiter.allowMailAction(clientIp, email)) {
            return;
        }
        if (!PasswordPolicy.isAcceptable(rawPassword)) {
            throw new PasswordPolicyViolationException();
        }
        // Hash before branching on existence so every branch below pays
        // Argon2id's deliberately-slow cost equally; otherwise response time
        // reveals which branch a given email took.
        var passwordHash = passwordEncoder.encode(rawPassword);

        var existingUser = userStore.findByEmail(email);
        if (existingUser.isPresent()) {
            signupOverExistingAccount(existingUser.get(), passwordHash);
            return;
        }
        try {
            var userId = userStore.createUnverified(email, passwordHash);
            sendVerificationLink(userId, email);
        } catch (DataIntegrityViolationException raced) {
            // Two concurrent signups for the same new address: the loser's
            // insert is rejected by the unique-email index. The winner already
            // sent that address a verification link, so there is nothing left
            // for the loser to do.
        }
    }

    private void signupOverExistingAccount(User existingUser, String passwordHash) {
        if (existingUser.verified()) {
            mailDispatcher.dispatch(AlreadyRegisteredMail.build(existingUser.email()));
            return;
        }
        userStore.updatePasswordHash(existingUser.id(), passwordHash);
        sendVerificationLink(existingUser.id(), existingUser.email());
    }

    @Transactional
    public void resendVerification(String email, String clientIp) {
        if (!rateLimiter.allowMailAction(clientIp, email)) {
            return;
        }
        var user = userStore.findByEmail(email).orElse(null);
        if (user == null || user.verified()) {
            return;
        }
        sendVerificationLink(user.id(), user.email());
    }

    private void sendVerificationLink(long userId, String email) {
        var verificationLink = issueVerificationLink(userId);
        mailDispatcher.dispatch(
            VerificationMail.build(email, verificationLink, properties.verificationTokenTtl()));
    }

    public LoginOutcome login(String email, String rawPassword, String clientIp) {
        // Checked first so a throttled request never pays the Argon2id cost
        // below, whether or not the submitted password would have matched.
        if (!rateLimiter.allowLogin(clientIp, email)) {
            return new LoginOutcome.InvalidCredentials();
        }
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
     * A session can only resolve to a row created by {@link #login}, and
     * nothing removes that row afterward — a miss here is an invariant
     * violation, not a routine user-facing error.
     */
    public User requireUser(long userId) {
        return userStore.findById(userId)
            .orElseThrow(() -> new IllegalStateException(
                "Session principal has no matching user row: " + userId));
    }

    @Transactional
    public void switchMediaType(long userId, String mediaType) {
        userStore.updateLastMediaType(userId, mediaType);
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

    @Transactional
    public void requestPasswordReset(String email, String clientIp) {
        if (!rateLimiter.allowMailAction(clientIp, email)) {
            return;
        }
        var user = userStore.findByEmail(email).orElse(null);
        if (user == null || !user.verified()) {
            return;
        }
        var resetLink = issueResetLink(user.id());
        mailDispatcher.dispatch(
            PasswordResetMail.build(user.email(), resetLink, properties.resetTokenTtl()));
    }

    private String issueResetLink(long userId) {
        var rawToken = tokenService.issue(TokenKind.RESET, userId);
        return properties.resetUrlTemplate().replace("{token}", rawToken);
    }

    @Transactional
    public TokenConsumption resetPassword(String rawToken, String newRawPassword) {
        var outcome = tokenService.consume(TokenKind.RESET, rawToken);
        if (outcome instanceof TokenConsumption.Consumed consumed) {
            // Checked only once the token is known live, so a dead link is always
            // reported as such (410) even when the submitted password also fails
            // policy — the token being dead is the more useful thing to tell the
            // caller, and throwing here rolls back the consume() burn above too.
            if (!PasswordPolicy.isAcceptable(newRawPassword)) {
                throw new PasswordPolicyViolationException();
            }
            userStore.updatePasswordHash(consumed.userId(), passwordEncoder.encode(newRawPassword));
            sessionStore.invalidateAll(consumed.userId());
        }
        return outcome;
    }
}
