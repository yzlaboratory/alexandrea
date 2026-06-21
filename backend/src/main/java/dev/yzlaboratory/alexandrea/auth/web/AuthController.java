package dev.yzlaboratory.alexandrea.auth.web;

import dev.yzlaboratory.alexandrea.auth.AuthService;
import dev.yzlaboratory.alexandrea.auth.EmailAlreadyRegisteredException;
import dev.yzlaboratory.alexandrea.auth.TokenConsumption;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The auth HTTP surface under {@code /api/auth}. Signup and resend always answer
 * the same way regardless of account state (202, "check your email"), so the
 * response cannot reveal whether an address is registered (ADR 0024). The one
 * leak — a password outside policy — describes the caller's own request, not
 * stored state, so reporting it (400) reveals nothing.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Always 202. A duplicate email is swallowed into the generic response here
     * rather than in the service, so the enumeration-safe shape is a property of
     * the HTTP boundary and the service stays free to signal the real conflict.
     */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void signup(@Valid @RequestBody SignupRequest request) {
        try {
            authService.signup(request.email(), request.password());
        } catch (EmailAlreadyRegisteredException alreadyRegistered) {
            // Intentionally indistinguishable from a fresh signup (ADR 0024).
        }
    }

    /** Always 202 — never reveals whether the address exists or is verified. */
    @PostMapping("/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resend(@Valid @RequestBody ResendRequest request) {
        authService.resendVerification(request.email());
    }

    /**
     * 200 when the link activates the account; 410 Gone when it is expired,
     * already used, or unknown — all collapsed to one body carrying
     * {@code canResend: true} so the SPA offers a resend without learning which
     * rejection it hit.
     */
    @PostMapping("/verify")
    public ResponseEntity<?> verify(@Valid @RequestBody VerifyRequest request) {
        var outcome = authService.verify(request.token());
        return switch (outcome) {
            case TokenConsumption.Consumed consumed ->
                ResponseEntity.ok(new VerifyResponse(true));
            case TokenConsumption.Expired expired ->
                ResponseEntity.status(HttpStatus.GONE).body(rejectedLink());
            case TokenConsumption.Rejected rejected ->
                ResponseEntity.status(HttpStatus.GONE).body(rejectedLink());
        };
    }

    private ProblemDetail rejectedLink() {
        var problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.GONE, "This verification link is no longer valid.");
        problem.setTitle("Verification link expired or already used");
        problem.setProperty("canResend", true);
        return problem;
    }

    public record VerifyResponse(boolean verified) {}
}
