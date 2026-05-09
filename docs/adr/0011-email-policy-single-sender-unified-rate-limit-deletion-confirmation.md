# Email policy: single transactional sender, unified rate limit, deletion confirmation

Email is the only out-of-band communication channel in the app. We
pin three cross-cutting policies that apply to every email the
service sends.

## 1. Single transactional sender

All transactional mail — verification, password reset, email-change
verification (to the new address), email-change notification (to the
old address), and account-deletion confirmation — is sent from a
single address: `noreply@<domain>`.

Reply handling is an **explicit reject** (a bounce with a short
"this mailbox is unmonitored — please use the in-app flows"
message), not a silent drop. Users who reply get a clear "your reply
went nowhere" signal rather than the impression that they wrote to
support.

A future support address (`hello@`, `support@`) can be added later
without changing transactional sender mechanics.

## 2. Unified rate limit per recipient address

Every email-sending flow draws from a single shared rate-limit
bucket scoped per recipient address:

- **At most 1 email per minute per address** across all flows.
- **At most 5 emails per hour per address** across all flows.

The bucket is *shared*, not per-flow. A user (or attacker) cannot
rotate through "resend verification → request password reset →
request email change → resend verification …" to spam a target's
inbox; all five count against the same hourly cap.

If a flow tries to send email when the bucket is empty, the user
sees a generic "you have requested too many emails recently — please
wait" message. The flow's other side effects (creating a token,
recording the request) are also blocked: no token-without-email
states.

This subsumes the resend-verification limits already in
`create-account.md`. Those scenarios remain accurate but are now
instances of this policy, not exceptions to it.

## 3. Account deletion sends a confirmation email

When a User completes account deletion (modal-confirmed,
password-gated, per `manage-account.md`), a deletion-confirmation
email is sent to the address that **was** the account email
immediately before deletion. The email reads "your account was
deleted at <time>" and provides no recovery path (the deletion is
already irreversible per the spec).

The reason to send it: inbox evidence. In the rare case a hijacked
session deletes a user's account, the email is the first signal the
user has that something happened. The cost is negligible — one
extra email per account deletion.

This email is exempt from the unified rate limit (it is a one-off
per account, not user-triggered in a way that could be abused).

## v1 transport: AWS SES

The v1 transport is **AWS SES** with **Easy DKIM** (SES creates
and rotates the DKIM keys; Route 53 holds the published CNAMEs).
SPF and DMARC records are likewise published in the same Route 53
hosted zone for full alignment. Outbound calls from the EC2
instance use IAM role-based credentials, not long-lived secrets.

Operational caveat: SES starts in **sandbox mode** — only
verified addresses can receive mail. Production access requires
an AWS support request and typically clears in 24–48h. Plan to
file the request during dev so the launch isn't blocked on it.

The transport sits behind a `TransactionalEmailSender` interface
in the backend so swapping providers later (Postmark, Resend, …)
is a single-class change. The SES decision is the v1 binding,
not a permanent commitment.

## Consequences

- **One transport configuration, one DKIM/SPF/DMARC alignment** —
  not five.
- **Adding a new email-sending flow** must use the same bucket and
  the same sender. There is no "this is just an admin email, it's
  fine to bypass the limit" path.
- **Operational delivery failures** (spam-foldering, recipient-
  side bounces) are not handled by this ADR. Pages that trigger
  email show "if you don't see it within 5 minutes, check spam or
  request again" copy, and the existing rate-limited resend paths
  remain the only retry mechanism.
