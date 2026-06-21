package dev.yzlaboratory.alexandrea.auth.web;

import dev.yzlaboratory.alexandrea.auth.PasswordPolicyViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates auth domain exceptions to HTTP responses for {@link AuthController}.
 *
 * <p>A password outside policy is the one signup failure a caller may be told
 * about: it describes their own request, not stored account state, so a 400
 * leaks nothing an attacker could use to enumerate accounts (ADR 0024).
 */
@RestControllerAdvice(assignableTypes = AuthController.class)
class AuthExceptionHandler {

    @ExceptionHandler(PasswordPolicyViolationException.class)
    ProblemDetail handlePasswordPolicyViolation(PasswordPolicyViolationException violation) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, violation.getMessage());
    }
}
