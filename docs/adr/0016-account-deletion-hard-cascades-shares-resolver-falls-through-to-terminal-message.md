# Account deletion hard-cascades Shares; the resolver falls through to a single "no longer active" terminal message

When a User deletes their account per `manage-account.md`, **every row
the User authored is hard-deleted** — watchlists, libraries, ratings,
completion dates, sessions, per-(user, surface, media_type) preferences,
the user's email-rate-limit bucket entries, every outstanding token
they hold, **and every Share they ever created — active, revoked, and
expired alike**. Nothing is tombstoned.

The Share-token resolver renders the **"This share link is no longer
active"** terminal message for **any token that does not resolve to a
currently-active Share**. That single rule covers four cases with one
implementation:

- **Revoked Share** — Share row exists with `status = revoked`.
- **Expired Share** — Share row exists with `expiry < now()`.
- **Deleted-Owner Share** — Share row no longer exists; cascaded with
  the User row.
- **Never-existed token** — Share row never existed.

The Friend cannot tell which case they're in, and does not need to.

## Why hard cascade rather than tombstoning

A tombstone variant — `owner_user_id` goes NULL on Owner deletion, the
Share row sticks around with a `deleted_owner` flag — was the obvious
alternative. Rejected because:

- **The terminal-message contract is satisfied without it.** The spec
  guarantee in `manage-account.md` is *"Friends with the URL see 'no
  longer active'"* — option (a) and option (b) both deliver that.
  Option (b) adds nothing user-visible.
- **Right-to-erasure is cleaner.** A deleted account leaves no row in
  any table that says *"this used to belong to a User."* Future
  regulatory pressure (`OOS.md` item 14 deferred a portability
  affordance — erasure is its sibling) lands better against a clean
  cascade.
- **The resolver is simpler.** One rule — *"unknown or inactive token
  → terminal message"* — replaces a per-status switch. The unknown-
  token branch is no longer special-cased; it folds into the same
  path.
- **No tombstone-distinguishability oracle.** A logged-out probe of a
  random token gets the same response shape as a probe of a real
  deleted-Owner token. There is no information leak about which
  tokens were ever issued.

Accepted cost: **forensics evaporate on deletion.** Operators cannot
look up "did this token ever exist on this account?" after the User
row is gone. For a personal tracker with no support team and no abuse
investigation surface, this is not a real loss; if it ever becomes
one, an audit-log table that survives cascade is a focused later
addition.

## Consequences

- **The Shares table has a `user_id` foreign key with `ON DELETE
  CASCADE`** — same as every other per-user table per ADR 0014.
- **The Share-resolver endpoint never returns 404 for a token shape
  that parses.** It returns the Share view (active) or the terminal
  message page (everything else). 404s only appear for malformed
  routes, not for "Share not found."
- **The active-Shares listing on the Owner's Shares tab
  (`manage-shares.md`) is unchanged** — it already shows only active
  Shares from the live Share rows. After Owner deletion there is no
  Owner to show a tab to.
- **A revoked/expired Share row is retained on the live account** so
  the Owner's *own* user history (should we ever expose one — see
  `OOS.md` item 12) can distinguish revoked from expired. Cascade
  only fires when the Owner is deleted.
- **The "every Share I ever created — active, revoked, or expired"
  language in `manage-account.md` remains accurate** because the
  terminal handler covers them all uniformly post-deletion. No spec
  edit is required.
