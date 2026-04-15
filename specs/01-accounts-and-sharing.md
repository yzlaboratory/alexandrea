# Accounts and Sharing

## Why this exists

Two people need to collaborate on lists and see each other's watch progress, but they also need private space — a personal watchlist Kira keeps for solo viewing shouldn't clutter the shared "couch on Saturday night" list. The account model has to support both without making the UI feel like a permissions dashboard.

## The shape of an account

Every user has:
- An email and a display name (e.g., "Kira", "M").
- A personal avatar color or image — used to tag who added a title to a list, who marked something watched, and whose rating is whose.
- A private library of their own watch history and ratings.
- Zero or more **shared spaces** they belong to.

## Shared spaces

A shared space is a group of people who share watchlists and can see each other's progress. The initial space is just Kira and her girlfriend.

### Scenario: Kira invites her girlfriend

Kira creates her account, opens the app, and sees an empty home screen with a prompt: "Watching with someone? Invite them." She taps it, types her girlfriend's email, and sends an invite. Her girlfriend gets a link, creates an account, accepts, and is now in the shared space "Kira & M."

From this moment on:
- Any watchlist Kira marks as **shared** is editable by both of them.
- Any watchlist Kira marks as **personal** is invisible to M.
- Each person's watch history and ratings are their own. M can see that Kira watched *The Bear S3E4* and gave it 4/5 on "story," but M's own progress through *The Bear* is tracked independently.

### Scenario: Adding a third person later

A friend, Sam, is visiting for a week and wants to join for a specific rewatch of *Severance*. Kira creates a new shared space called "Severance Club" and invites Sam. Sam does not get access to the "Kira & M" space. When Sam leaves, Kira can archive the space without deleting anyone's personal ratings.

## Private vs. shared data

| Data | Scope |
| --- | --- |
| Watch history (what episodes you've seen) | Per user, visible to shared-space members |
| Ratings | Per user, visible to shared-space members |
| Personal watchlists | Per user, invisible to others |
| Shared watchlists | Per shared space, editable by all members |
| Title metadata (posters, RT scores) | Global, same for everyone |

A rule of thumb: **lists are collaborative, opinions are personal.** A couple agrees on what to watch; they don't have to agree on what they thought of it.

## Presence and attribution

When M adds *Poor Things* to the shared "Movie Night" list, Kira opens the app later and sees the title with a small "added by M" tag. When Kira drags it up the list, M (on her own device) sees the list reorder in near-real-time if she has the app open, or on next refresh otherwise. Every mutation carries the identity of who did it.

## Authentication

### Sign-up and sign-in methods

- **Email + password** — the default. Minimum 10 characters, checked against a common-password blocklist. Passwords are salted and hashed with a modern KDF (Argon2id or bcrypt with a high cost factor).
- **Sign in with Apple** — first-class, especially on iOS.
- **Sign in with Google** — first-class on Android and web.
- Other OAuth providers may follow based on demand; the same account can have multiple auth methods attached over time.

### Email verification

A confirmation email is sent on signup with a time-limited link (24 hours). Until verified, the account can browse and build a personal library but cannot join or create shared spaces. Re-send is rate-limited.

### Password reset

"Forgot password" triggers a single-use reset link, valid for 60 minutes, invalidated on first use. The reset email never contains the password. Successful resets sign out all existing sessions except the current one.

### Two-factor authentication

Optional TOTP (authenticator app) 2FA, available from Settings. On enabling, the user downloads eight backup codes. 2FA is encouraged but not required. SMS 2FA is deliberately not offered (security and cost reasons).

### Session and device management

Settings → Security shows a list of active sessions with device name, approximate location (by IP), last-seen timestamp, and a *Sign out* button per session. A *Sign out everywhere* action is one click and triggers a fresh email notification.

Sessions expire after 90 days of inactivity. A password reset, email change, or 2FA change invalidates all other sessions.

### Suspicious-activity notifications

The user receives an email any time:

- A new device signs in to their account.
- Their email, password, or 2FA settings change.
- They're added to a new shared space.

These emails include an "if this wasn't you" link that jumps to the sessions list.

## Invitations

- Invite links are time-limited (7 days) and single-use unless the inviter toggles "reusable link" (max 10 uses).
- The inviter can revoke a pending invite from the shared-space settings.
- An invited email that doesn't yet have an account lands on the sign-up flow with the invite pre-attached; the account is auto-joined to the shared space on verification.
- Re-inviting someone who declined re-opens the invite without surfacing their decline (avoids awkwardness).

## Account edge cases

- **Leaving a shared space.** If M leaves "Kira & M," the shared lists stay with Kira (she was an equal owner), but M's ratings and watch history remain on M's own account. No data is destroyed.
- **Deleting an account.** Personal data is removed. Contributions to shared lists stay, but attribution becomes "former member."
- **Offline account.** Reading the app (browsing, seeing your watchlists) works offline. Marking things watched queues and syncs when back online; list reordering does not apply offline because it would conflict with the other user's edits.
- **Account deletion.** Initiated from Settings → Account → Delete. A confirmation email is sent; after the user clicks through, the account enters a 30-day soft-deleted state during which sign-in restores it. After 30 days, personal data is purged, and shared-space contributions are re-attributed to "former member."
- **Email change.** Requires password re-entry and confirmation at the new address before it takes effect. The old address receives a notification with a revert link valid for 7 days.
