package dev.yzlaboratory.alexandrea.auth.web;

import dev.yzlaboratory.alexandrea.auth.InvalidCredentialsException;
import dev.yzlaboratory.alexandrea.auth.PasswordPolicyViolationException;
import dev.yzlaboratory.alexandrea.auth.ResetLinkRejectedException;
import dev.yzlaboratory.alexandrea.auth.VerificationLinkRejectedException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AuthController.class)
class AuthExceptionHandler {

    /**
     * Stable discriminator on the password-policy 400 so the SPA can tell it apart
     * from a generic Bean Validation 400 (e.g. a malformed email) and surface the
     * length error against the right field instead of guessing from the status.
     */
    static final String PASSWORD_POLICY_PROBLEM_TYPE = "urn:alexandrea:auth:password-policy";

    @ExceptionHandler(PasswordPolicyViolationException.class)
    ProblemDetail handlePasswordPolicyViolation(PasswordPolicyViolationException violation) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, violation.getMessage());
        problem.setType(URI.create(PASSWORD_POLICY_PROBLEM_TYPE));
        return problem;
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail handleInvalidCredentials(InvalidCredentialsException invalid) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, invalid.getMessage());
    }

    @ExceptionHandler(VerificationLinkRejectedException.class)
    ProblemDetail handleVerificationLinkRejected(VerificationLinkRejectedException rejected) {
        var problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.GONE, "This verification link is no longer valid.");
        problem.setTitle("Verification link expired or already used");
        problem.setProperty("canResend", true);
        return problem;
    }

    @ExceptionHandler(ResetLinkRejectedException.class)
    ProblemDetail handleResetLinkRejected(ResetLinkRejectedException rejected) {
        var problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.GONE, "This password reset link is no longer valid.");
        problem.setTitle("Password reset link expired or already used");
        return problem;
    }
}
