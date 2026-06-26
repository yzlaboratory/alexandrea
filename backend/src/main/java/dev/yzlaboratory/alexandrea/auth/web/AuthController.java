package dev.yzlaboratory.alexandrea.auth.web;

import dev.yzlaboratory.alexandrea.auth.AuthService;
import dev.yzlaboratory.alexandrea.auth.EmailAlreadyRegisteredException;
import dev.yzlaboratory.alexandrea.auth.TokenConsumption;
import dev.yzlaboratory.alexandrea.auth.VerificationLinkRejectedException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signup and resend always answer the same way regardless of account state (202,
 * "check your email"), so the response cannot reveal whether an address is
 * registered (ADR 0024).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void signup(@Valid @RequestBody SignupRequest request) {
        try {
            authService.signup(request.email(), request.password());
        } catch (EmailAlreadyRegisteredException alreadyRegistered) {
            // Intentionally indistinguishable from a fresh signup (ADR 0024).
        }
    }

    @PostMapping("/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resend(@Valid @RequestBody ResendRequest request) {
        authService.resendVerification(request.email());
    }

    @PostMapping("/verify")
    public VerifyResponse verify(@Valid @RequestBody VerifyRequest request) {
        var outcome = authService.verify(request.token());
        return switch (outcome) {
            case TokenConsumption.Consumed _ -> new VerifyResponse(true);
            case TokenConsumption.Expired _, TokenConsumption.Rejected _ ->
                throw new VerificationLinkRejectedException();
        };
    }

    public record VerifyResponse(boolean verified) {}
}
