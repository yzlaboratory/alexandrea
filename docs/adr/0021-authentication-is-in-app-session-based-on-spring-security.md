# Authentication is in-app, session-based, on Spring Security (supersedes the kiraauth integration)

The entertainment library **owns** authentication. Identity (email, Argon2id
password hash, email-verification state), server-side sessions, and the
verification / password-reset / email-change tokens all live in this app's own
SQLite database (ADR 0014) and are managed by **Spring Security**. This
**supersedes** the earlier decision to consume **kiraauth** as an external
authentication backend — kiraauth is retired. The auth clauses of ADR 0014 and
the deletion-trigger of ADR 0016 are superseded accordingly.

## Why in-app rather than a separate service

kiraauth was a *separate, self-built* auth service: build cost plus permanent
operational surface, and a cross-service boundary on every request. For a
single-instance, personal-scale multi-user tracker that is more than the value
returned. Spring Security provides the dangerous primitives (password hashing,
session management, CSRF) so owning auth *in-process* is low-risk — the app
already runs on the JVM.

Rejected alternatives:

- **better-auth** — the trigger for revisiting this, but it is a
  TypeScript/Node library and cannot run on the JVM. Adopting it would have
  meant either rewriting the backend in Node or running a Node sidecar — the
  latter re-introduces the cross-runtime auth boundary that retiring kiraauth
  removed.
- **A hosted provider** (Clerk/Supabase Auth/etc.) — lowest code, but a vendor
  dependency and per-MAU pricing. We chose to own auth for control and zero
  marginal cost.

## Shape

- **Identity store.** A local `users` table (unique email, Argon2id hash,
  `verified` flag, timestamps) plus token tables (email-verification,
  password-reset, pending-email-change) and an email rate-limit bucket — all in
  SQLite. Every per-user row foreign-keys `users.id` with `ON DELETE CASCADE`
  (the schema shape ADR 0016 already assumed).
- **Password hashing.** Argon2id via Spring Security's
  `DelegatingPasswordEncoder`.
- **Sessions.** Server-side, persisted via **Spring Session JDBC** into SQLite,
  so a container redeploy does not log everyone out. The session cookie is
  `HttpOnly`, `Secure`, `SameSite=Lax`.
- **CSRF.** Auth is same-origin again (SPA + API behind one CloudFront virtual
  host, ADR 0014), so Spring Security's CSRF token filter is **enabled** and the
  SPA echoes the token on state-changing requests. This re-pins the CSRF
  strategy ADR 0014 had left TBD pending kiraauth.
- **Email verification gates access.** A newly signed-up account is unverified
  and cannot log in or reach protected surfaces until it verifies via a
  single-use, expiring link; resend is available (#8).
- **Transactional email.** Amazon SES (fits the AWS stack) for verification,
  password-reset, and email-change confirmation.
- **Re-auth + session hygiene.** Changing password or email requires the
  current password; a password change or reset invalidates the user's other
  sessions.

## v1 scope (Lean + self-service email change)

In: signup, email verification, login, logout, forgot-password reset, change
password, change email (re-verify the new address), basic rate-limiting on
login and reset.

Deferred (see #8 out-of-scope): **self-service account deletion — and thus
right-to-erasure has no v1 path**; social / magic-link / passkey login;
"log out everywhere" session management UI; breach-password (HIBP) checks;
auth audit log.

## Relationship to ADR 0016

ADR 0016's **Share-resolver terminal-message rule** (revoked / expired /
never-existed token → "no longer active") **remains in force**. Its
account-deletion **cascade** is **deferred** with self-service deletion —
nothing deletes accounts in v1, so the "deleted-Owner" token case cannot arise.
A future deletion feature re-activates that cascade, triggered **locally**
rather than by a kiraauth notification.

## Consequences

- The library now stores password hashes and PII (email) and sends email — it
  inherits the obligations kiraauth used to carry: correct hashing params,
  single-use/expiring/enumeration-safe tokens, and rate limiting.
- **No external auth dependency.** There is no kiraauth to be down, to
  introspect per request, or to coordinate cookies with.
- **Right-to-erasure has no v1 path.** Deferred (#8). This is the deliberate
  cost of the Lean scope; revisit if real users or a regulatory forcing
  function require it.
