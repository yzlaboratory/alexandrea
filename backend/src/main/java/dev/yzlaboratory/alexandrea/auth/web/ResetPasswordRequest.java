package dev.yzlaboratory.alexandrea.auth.web;

import jakarta.validation.constraints.NotBlank;

/**
 * Length is deliberately not a {@code @Size}: that counts UTF-16 chars, while
 * {@link dev.yzlaboratory.alexandrea.auth.PasswordPolicy} counts code points, so
 * the two disagree on astral-plane (emoji) passwords. The service is the single
 * judge of length.
 */
public record ResetPasswordRequest(
    @NotBlank String token,
    @NotBlank String newPassword
) {}
