# Password policy follows NIST 800-63B

Passwords must be at least 12 characters and must not appear in the
HaveIBeenPwned breach corpus (checked via the k-anonymity API at
**every point a new password is accepted**: account creation, password
change, and password reset). There are **no** mandatory character-class
rules — no required upper/lower/digit/symbol — and no forced rotation.

This deliberately follows NIST 800-63B's modern guidance: long
passphrases plus breach screening dominate classic complexity rules in
both security and usability. A reader expecting the classic rules will
naturally try to "fix" the absence of `must contain a symbol`; this
ADR exists to head that off.

## Storage: Argon2id

Passwords are stored as **Argon2id** hashes (not bcrypt, not
PBKDF2, not scrypt). Argon2id is the OWASP-recommended modern
default and is consistent with NIST 800-63B's memory-hard KDF
guidance. Initial parameters: `memory = 19 MiB`, `iterations = 2`,
`parallelism = 1` — OWASP's 2024 minimum that runs comfortably on
the v1 EC2 instance. Parameters are encoded inside the stored hash
string, so raising the cost factor later does not require a
schema change.

The v1 implementation uses Spring Security's `Argon2PasswordEncoder`
(Java-side) — no rolled-our-own crypto.

## HIBP availability is fail-open

If the HIBP k-anonymity API is unavailable when a new password is
submitted (timeout, 5xx, network failure, malformed response), the
password operation **proceeds without the breach screening**. The 12-
character minimum is still enforced locally; only the breach check is
skipped for that operation.

Implementation:

- Each call to HIBP uses a short timeout (~3s) with one retry (~3s),
  so a transient blip is invisible to the user.
- If both attempts fail, the password is accepted on the strength of
  the local rules alone.
- Every fail-open event is **logged** with a counter so an outage is
  visible to operators and the proportion of unscreened password
  admissions can be estimated after the fact.
- No user-facing message announces the bypass — the operation looks
  like a normal success.

The trade-off: during an HIBP outage, a small number of breached
passwords may be admitted. The alternative (fail-closed) was rejected
because it would block all signup, password-change, and password-reset
flows for the duration of the outage — turning a third-party
availability incident into a full account-recovery incident for our
users. We accept the rare and time-bounded exposure.
