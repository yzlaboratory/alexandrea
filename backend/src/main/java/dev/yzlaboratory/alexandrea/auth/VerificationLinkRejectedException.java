package dev.yzlaboratory.alexandrea.auth;

/**
 * Raised when a verification token is expired, already used, or unknown.
 *
 * <p>The web layer maps every reason to one 410 response (ADR 0024) so the SPA
 * offers a resend without learning which rejection it hit.
 */
public class VerificationLinkRejectedException extends RuntimeException {

    public VerificationLinkRejectedException() {
        super("Verification link is expired, already used, or unknown");
    }
}
