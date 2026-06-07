package dev.yzlaboratory.alexandrea.auth.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import dev.yzlaboratory.alexandrea.auth.PasswordPolicy;

/**
 * Signup payload (#19). The password length bounds are asserted here too so a
 * too-short/too-long password is a clean 400 from Bean Validation before the
 * service runs, with no DB touch. The service re-checks {@link PasswordPolicy}
 * as the single source of truth for callers that bypass the web layer.
 */
public record SignupRequest(
    @NotBlank @Email String email,
    @NotBlank
    @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH)
    String password
) {}
