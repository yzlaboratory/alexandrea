package dev.yzlaboratory.alexandrea.auth;

public class VerificationLinkRejectedException extends RuntimeException {

    public VerificationLinkRejectedException() {
        super("Verification link is expired, already used, or unknown");
    }
}
