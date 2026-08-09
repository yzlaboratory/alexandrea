package dev.yzlaboratory.alexandrea.auth.mail;

/** Mirrors the two reasons SES's own account-level suppression list acts on (ADR 0023's ses.tf). */
public enum UnsendableReason {
    BOUNCE,
    COMPLAINT
}
