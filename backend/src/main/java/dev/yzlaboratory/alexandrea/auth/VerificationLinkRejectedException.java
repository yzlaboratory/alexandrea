package dev.yzlaboratory.alexandrea.auth;

/**
 * The web layer maps every reason (expired, already used, unknown) to one 410
 * response (ADR 0024) so the SPA offers a resend without learning which it hit.
 */
public class VerificationLinkRejectedException extends RuntimeException {

    public VerificationLinkRejectedException() {
        super("Verification link is expired, already used, or unknown");
    }
}
