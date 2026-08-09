package dev.yzlaboratory.alexandrea.auth;

/**
 * The single-use links (email verification, password reset) share one
 * rejection shape — expired, already used, or unknown all read as "no longer
 * valid" — differing only in the copy shown and whether a resend affordance
 * applies.
 */
public enum SingleUseLinkKind {

    VERIFICATION(
        "Verification link is expired, already used, or unknown",
        "Verification link expired or already used",
        "This verification link is no longer valid.",
        true),
    RESET(
        "Password reset link is expired, already used, or unknown",
        "Password reset link expired or already used",
        "This password reset link is no longer valid.",
        false),
    EMAIL_CHANGE(
        "Email change link is expired, already used, or unknown",
        "Email change link expired or already used",
        "This email change link is no longer valid.",
        false);

    private final String rejectionMessage;
    private final String title;
    private final String detail;
    private final boolean canResend;

    SingleUseLinkKind(String rejectionMessage, String title, String detail, boolean canResend) {
        this.rejectionMessage = rejectionMessage;
        this.title = title;
        this.detail = detail;
        this.canResend = canResend;
    }

    public String rejectionMessage() {
        return rejectionMessage;
    }

    public String title() {
        return title;
    }

    public String detail() {
        return detail;
    }

    public boolean canResend() {
        return canResend;
    }
}
