# Auth enumeration-safety and unverified-account semantics

Alexandrea's in-app authentication (ADR 0021) must never let an
unauthenticated caller learn whether a given email is registered, yet it must
still give the *legitimate* owner of an unverified or mistyped account a way
forward. These two goals collide at three points — the login response, the
re-signup response, and the rate limiter — and the resolution is the same
principle applied three ways: **the response an outsider sees is invariant;
only effects gated behind a credential or delivered to the real inbox may
differ by account state.**

## Decisions

### 1. The "verify your email" reveal is gated behind a correct password

Login validates credentials **before** consulting the `verified` flag.

- Correct email **and** password, but unverified → show the "verify your
  email" + resend state.
- Wrong password, or unknown email → the single generic "email or password is
  incorrect" error.

Telling someone "this account is unverified" reveals the email is registered.
We only reveal it to a caller who has already supplied the correct password —
who has therefore effectively authenticated, so the disclosure leaks nothing to
an attacker who does not hold the password. Checking `verified` *before* the
password (the naive order) would turn login into a registration oracle.

### 2. Re-signup against an unverified account overwrites and re-sends

The signup response is identical for every email ("check your email"). The
*side effects* branch on stored state, which only the real inbox owner observes:

- **No account** → create an unverified account, send a verification link.
- **Verified account** → no change; the address receives a "you already have an
  account" notice instead of a link.
- **Unverified account** → treat it as **unclaimed**: overwrite its password
  hash with the newly-submitted password, invalidate any outstanding
  verification token, and send a fresh verification link.

Overwriting a stored password on signup is surprising, so the reasoning is
recorded here: an unverified account has never been proven to belong to anyone,
and it cannot be *claimed* without the verification link, which is delivered
only to the real address. An attacker who re-signs up over a victim's unverified
account therefore gains nothing — they cannot receive the link — while the
legitimate owner who mistyped their password or lost the first email is
rescued. This makes "resend" and "re-signup" converge for unverified accounts
and removes a dead-end state.

### 3. Rate limiting is keyed on IP **and** target email, across every mail-sending endpoint

Every endpoint that sends mail — signup, password-reset request, verification
resend, email-change request — plus login is rate-limited. Each request is
counted against **both** the client IP and the target email address; whichever
bucket trips first throttles the request. Throttled responses keep the same
generic, enumeration-safe shape as success.

Per-IP keying alone lets a botnet bomb one victim's inbox from many addresses;
per-email keying alone lets one host probe many emails. Keying on both closes
both. The buckets are the SQLite rate-limit table of ADR 0014; default windows
(≈5/hour for mail endpoints, ≈10 per 15 min for login) are externalised config,
not pinned here.

## Consequences

- **Enumeration-safety is a cross-cutting invariant, not a per-endpoint
  afterthought.** Any new auth endpoint inherits these rules; changing the
  posture later touches every flow, which is why it is recorded as a decision.
- **Signup is idempotent-ish for unverified state** but strictly read-only
  against verified accounts — the two branches must stay distinct or the
  overwrite path becomes an account-takeover bug.
- **The rate limiter needs both keys present** on every guarded request; an
  endpoint that forgets the email key silently re-opens the inbox-bomb vector.
- This ADR refines ADR 0021; it does not change the storage, hashing, or session
  model decided there.
