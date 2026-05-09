# Auth events invalidate all sessions; password-change preserves only the session that authenticated it

Every authentication-relevant change to an account invalidates
every active session for that account, on every device. The single
exception is password-change, which preserves the session that
authenticated the change (and only that session); a brand-new
account has no sessions to invalidate, so account-creation
verification has nothing to do.

The binding matrix:

| Trigger | Other sessions | Session that performed the action |
|---|---|---|
| Password change (logged-in) | Invalidated | Preserved |
| Password reset (link from email) | Invalidated | N/A — user redirected to login |
| Email-change verification | Invalidated | Invalidated — user logs in afresh with the new address |
| Email-change revert (pre-verification) | Invalidated | Invalidated |
| Email-change revert (post-verification) | Invalidated | Invalidated |
| Account deletion | Invalidated | Invalidated |
| Account-creation verification | (none exist) | (none exist) |

The matrix is the source of truth. Specs reference this ADR rather
than re-deriving the rule per flow.

### Side-effect: password change invalidates any pending email-change verification token

Beyond the session matrix, **password change** carries one
additional invalidation: if an email-change verification token is
outstanding for the account at the moment the password is changed,
that token is also invalidated.

The reason is the threat model: the *only* recourse the original-
address holder has against a silent in-flight email change (after
the 7-day revert link was removed) is *"log in with the old address
— still valid until the new one verifies — and change the password."*
Killing the attacker's session is necessary but not sufficient,
because the pending verification token sits in the new-address
inbox the attacker controls. Without invalidating that token,
password change kicks the session but leaves the verification link
live; the attacker clicks it and the change still lands. Tying
token invalidation to password change closes the recourse loop.

This is the only auth event that touches a non-session token.
Other events (reset, email-change verification, deletion) either
predate any pending email-change or implicitly subsume it.

## Why kill all on every event except password-change

The threat model behind every entry in the matrix is "an attacker
might already hold a session." Reset is invoked precisely because
the user suspects compromise; revert is invoked because the
original-address holder is rejecting an in-flight change they may
not have authorised; deletion may itself be the attacker's final
act. Killing all sessions makes each of these events flush any
in-flight intruder, with no exception path for an attacker to
preserve their foothold.

Password-change is the lone exception because the change is
authenticated by the **current session** in real time — the user
typed the current password to confirm. That session has therefore
proven itself to be the legitimate user at the moment of action,
which is the strongest possible evidence we have. Preserving it
costs nothing; killing it would force the user to re-login on the
device they were just authenticated on for no security gain.

Email-change verification deliberately does **not** preserve the
session that initiated the change, because verification happens via
a click on a link in an email inbox — that click does not re-prove
the initiator's identity at the moment of action, and the link may
even be opened in a different browser. Treating verification as
"kill all and require fresh login on the new address" is the
honest semantic.

## Why not preserve the current session on every event

Industry norm tends to preserve the current session more
aggressively (e.g. email-change verification typically keeps the
verifying browser logged in). We chose against that norm because:

- It introduces per-flow exception logic that is hard to keep
  consistent. Once one auth event has a "preserve current"
  carve-out that lacks the password-change-style real-time
  re-authentication, the door is open for every future auth flow
  (2FA setup, OAuth linking, recovery code use) to add its own
  carve-out and drift back toward inconsistency.
- The UX cost of one extra login on a security event is small.
  The security cost of leaving an intruder session alive after
  the legitimate user has just performed an account-recovery
  action is large and silent.

## Why not kill all on password-change too

A future tightening could remove the password-change carve-out and
make every auth event kill all sessions uniformly. We rejected
that because:

- The current session has just re-authenticated by typing the
  current password. That is exactly the proof we would otherwise
  ask for after a forced re-login — forcing the re-login regardless
  is friction without security gain.
- Users routinely change passwords proactively (rotation hygiene,
  post-breach-news worry) on a single device; kicking them off
  that device on every rotation would discourage the behaviour we
  want to encourage.

## Consequences

- **Sessions must be revocable server-side.** Stateless tokens
  (e.g. JWT with no revocation list) cannot satisfy this matrix
  without a blacklist that defeats their stateless property. The
  session implementation is therefore a server-side store keyed
  by an opaque session id. This is consistent with the sliding
  30-day session in `log-in.md` (which already requires a
  server-side touch-on-use).
- **Adding a new auth-relevant flow is a binding action.** Any
  future auth event — 2FA enrollment, OAuth account linking,
  recovery code consumption, account merge — must declare which
  matrix row it belongs to. The default is "kill all"; the
  password-change-style "preserve current" exception is only
  available when the flow re-authenticates the current session
  in real time.
- **The "remember me" / device trust feature, if added, must
  still respect this matrix.** A trusted device persisting
  beyond a session-invalidation event would silently undermine
  every row in the table. Any such feature is a deliberate
  exception, captured in its own ADR.
- **Specs reference this ADR rather than restating the rule.**
  `log-in.md`, `manage-account.md`, and any future auth spec
  should link here for the session-invalidation behaviour rather
  than re-deriving it inline. Per-spec scenarios still assert
  "every active session is invalidated immediately" so the
  Gherkin remains testable, but the rationale lives here.
