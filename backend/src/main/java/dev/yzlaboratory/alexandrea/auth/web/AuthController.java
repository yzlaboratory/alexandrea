package dev.yzlaboratory.alexandrea.auth.web;

import dev.yzlaboratory.alexandrea.auth.AuthService;
import dev.yzlaboratory.alexandrea.auth.AuthenticatedUser;
import dev.yzlaboratory.alexandrea.auth.InvalidCredentialsException;
import dev.yzlaboratory.alexandrea.auth.LoginOutcome;
import dev.yzlaboratory.alexandrea.auth.TokenConsumption;
import dev.yzlaboratory.alexandrea.auth.VerificationLinkRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final SecurityContextRepository securityContextRepository;

    public AuthController(
        AuthService authService,
        SecurityContextRepository securityContextRepository
    ) {
        this.authService = authService;
        this.securityContextRepository = securityContextRepository;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void signup(@Valid @RequestBody SignupRequest request) {
        authService.signup(request.email(), request.password());
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

    @PostMapping("/login")
    public LoginResponse login(
        @Valid @RequestBody LoginRequest request,
        HttpServletRequest servletRequest,
        HttpServletResponse servletResponse
    ) {
        var outcome = authService.login(request.email(), request.password());
        return switch (outcome) {
            case LoginOutcome.Authenticated authenticated -> {
                var user = authenticated.user();
                establishSession(
                    new AuthenticatedUser(user.id(), user.email()), servletRequest, servletResponse);
                yield new LoginResponse(true, user.lastMediaType());
            }
            case LoginOutcome.UnverifiedAccount _ -> new LoginResponse(false, null);
            case LoginOutcome.InvalidCredentials _ -> throw new InvalidCredentialsException();
        };
    }

    /**
     * {@code request.changeSessionId()} swaps the id post-authentication so a
     * session id issued before login can't be reused (fixation).
     */
    private void establishSession(
        AuthenticatedUser user, HttpServletRequest request, HttpServletResponse response
    ) {
        var authentication = new UsernamePasswordAuthenticationToken(user, null, List.of());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        securityContextRepository.saveContext(context, request, response);
    }

    public record LoginResponse(boolean verified, String lastMediaType) {}

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
        HttpServletRequest request, HttpServletResponse response, Authentication authentication
    ) {
        new SecurityContextLogoutHandler().logout(request, response, authentication);
    }

    @GetMapping("/session")
    public SessionResponse session(@AuthenticationPrincipal AuthenticatedUser principal) {
        var user = authService.requireUser(principal.userId());
        return new SessionResponse(user.email(), user.lastMediaType());
    }

    public record SessionResponse(String email, String lastMediaType) {}

    @PostMapping("/media-type")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void switchMediaType(
        @Valid @RequestBody MediaTypeRequest request,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        authService.switchMediaType(principal.userId(), request.mediaType());
    }
}
