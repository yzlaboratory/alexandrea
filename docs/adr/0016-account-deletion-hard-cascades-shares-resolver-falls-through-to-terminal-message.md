# Library hard-cascades on a kiraauth user-deleted notification; the Share resolver falls through to a single "no longer active" terminal message

> **Status — superseded in part by ADR 0021.** kiraauth is gone; the library
> now owns identity in-app (ADR 0021). v1 ships **no self-service account
> deletion**, so the deletion **cascade** below does not fire in v1 and
> right-to-erasure is deferred (see #8). The **Share-resolver terminal-message
> rule** below stays in force for revoked, expired, and never-existed tokens;
> the *deleted-Owner* case cannot arise until a deletion feature exists, at
> which point the cascade re-activates — triggered locally, not by a kiraauth
> notification. The text below is retained as the original record.

The entertainment library does not own user identities — kiraauth
does. The library only learns that a User has been deleted when
kiraauth fires a **user-deleted notification** (see kiraauth ADR
0004 for the integration shape; the mechanics are TBD but the
contract that "kiraauth tells consuming apps about deletions" is
fixed).

When the library receives a user-deleted notification carrying a
User ID, **every library row authored by that User is hard-deleted**
— watchlists, libraries, ratings, completion dates, per-(user,
surface, media_type) preferences, **and every Share they ever
created — active, revoked, and expired alike**. Nothing is
tombstoned.

The Share-token resolver renders the **"This share link is no longer
active"** terminal message for **any token that does not resolve to a
currently-active Share**. That single rule covers four cases with one
implementation:

- **Revoked Share** — Share row exists with `status = revoked`.
- **Expired Share** — Share row exists with `expiry < now()`.
- **Deleted-Owner Share** — Share row no longer exists; cascaded
  on the user-deleted notification.
- **Never-existed token** — Share row never existed.

The Friend cannot tell which case they're in, and does not need to.

## Why hard cascade rather than tombstoning

A tombstone variant — `owner_user_id` goes NULL on user-deleted
notification, the Share row sticks around with a `deleted_owner`
flag — was the obvious alternative. Rejected because:

- **The terminal-message contract is satisfied without it.** The
  guarantee is *"Friends with the URL see 'no longer active'"* —
  option (a) and option (b) both deliver that. Option (b) adds
  nothing user-visible.
- **Right-to-erasure is cleaner.** A user-deleted notification
  leaves no row in any table that says *"this used to belong to a
  User."* Future regulatory pressure (the deferred-items backlog #9 defers a
  portability affordance — erasure is its sibling) lands better
  against a clean cascade.
- **The resolver is simpler.** One rule — *"unknown or inactive
  token → terminal message"* — replaces a per-status switch. The
  unknown-token branch is no longer special-cased; it folds into
  the same path.
- **No tombstone-distinguishability oracle.** A logged-out probe
  of a random token gets the same response shape as a probe of a
  real deleted-Owner token. There is no information leak about
  which tokens were ever issued.

Accepted cost: **forensics evaporate on the user-deleted
notification.** Operators cannot look up "did this token ever
exist on this account?" after the cascade has run. For a personal
tracker with no support team and no abuse investigation surface,
this is not a real loss; if it ever becomes one, an audit-log
table that survives cascade is a focused later addition.

## Consequences

- **The Shares table has a `user_id` foreign key with `ON DELETE
  CASCADE`** — same as every other per-user table per ADR 0014.
  The user-deleted handler issues a single `DELETE FROM users
  WHERE id = ?` and the cascade fans out.
- **The user-deleted handler must be idempotent.** kiraauth's
  notification mechanics (per kiraauth ADR 0004) may retry; a
  second delivery for the same User ID after the cascade has run
  must be a no-op, not an error.
- **The Share-resolver endpoint never returns 404 for a token shape
  that parses.** It returns the Share view (active) or the terminal
  message page (everything else). 404s only appear for malformed
  routes, not for "Share not found."
- **The active-Shares listing on the Owner's Shares tab
  (#1) is unchanged** — it already shows only active
  Shares from the live Share rows. After the cascade there is no
  Owner left to show a tab to.
- **A revoked/expired Share row is retained on the live account** so
  the Owner's *own* user history (should we ever expose one — see
  the deferred-items backlog #9) can distinguish revoked from expired. Cascade
  only fires on the user-deleted notification.
