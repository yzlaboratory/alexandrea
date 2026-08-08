package dev.yzlaboratory.alexandrea.auth;

public class ResetLinkRejectedException extends RuntimeException {

    public ResetLinkRejectedException() {
        super("Password reset link is expired, already used, or unknown");
    }
}
