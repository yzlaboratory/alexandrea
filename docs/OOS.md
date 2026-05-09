# Out of Scope

Features, behaviours, and refinements that have been considered and **explicitly
deferred** to a later iteration. Each entry is something we said "not now" to,
not something we forgot — keep this file honest.

When work begins on one of these, move it into a normal spec under
`docs/specs/` and remove the entry from this file.

## Deferred features

1. **Full-text search across non-title fields.** v1 search (catalog browse,
   watchlist, library) matches **title only**. Searching by cast, author,
   director, genre, description, or any other metadata field is deferred.
2. **Personalised catalog recommendations.** The browse page lands on what is
   currently popular in the external catalog. Recommendations driven by the
   user's library (e.g. "because you liked …") are deferred.
3. **Cross-media-type unified views.** Watchlists and libraries are strictly
   per media type. A combined "everything I want to consume" or "everything
   I have rated" view across Movies / TV / Books / Games is deferred.
4. **Editing an existing share link.** Shares are immutable — to change a
   filter, sort, or expiry, the owner must revoke and create a new link.
   In-place editing of an existing share is deferred.
5. **Hand-picking entries for a share.** Shares are defined by a captured
   filter + sort combination only. The ability to manually include or exclude
   specific entries on top of the filter is deferred.
6. **Snapshot share links.** All shares are live — they reflect the current
   state of the owner's library every time the friend loads the page.
   Frozen-in-time snapshot links are deferred.
7. **Alternative authentication methods.** v1 supports email + password with
   email verification. OAuth providers (Google, GitHub, …) and passwordless
   magic links are deferred.
8. **Per-aspect Characteristics for Books and Games.** v1 Ratings for Books
   and Games carry only the mandatory Overall Enjoyment Characteristic.
   Optional per-aspect Characteristics (e.g. Writing for Books; Gameplay,
   Story, Visuals, Music for Games) are deferred to a later version once
   the v1 Movies/TV form has shaken out.
9. **Share-link creation rate limiting.** v1 places no per-Owner cap on the
   number of Share links created or held active. Revisit if a real abuse
   pattern appears.
10. **Share usage observability for the Owner.** The Shares tab shows the
    captured filter+sort, expiry, and "Copy URL" / "Revoke" affordances
    only — no last-opened timestamp, view count, or per-friend signal.
    Deferred for privacy and simplicity; revisit if owners ask for it.
11. **Backfilled / custom Completion Dates.** v1 always records today's
    date when an entry is Completed (whether first-time or re-completed).
    Editing or backfilling a Completion Date — useful for "I watched this
    last weekend" and essential for bulk historical imports — is
    deferred. Likely re-emerges alongside any future bulk-import feature.
12. **Recent-activity / per-user audit surface.** A timeline of writes
    to a user's account (added X to watchlist, completed Y, created Share
    Z) is not exposed in v1. Originally motivated by the email-change
    revert flow (since removed); still useful for general "what changed?"
    questions. Deferred — the user remains in control of cleanup without
    one, just less efficiently.
13. **Explicit "Cancel pending email change" affordance** on the Account
    settings page. v1 has two implicit cancellation paths — initiating a
    fresh email change (which supersedes the pending one per
    `manage-account.md`) and changing the password (which invalidates
    any pending email-change verification token per ADR 0012). An
    explicit cancel button for the benign typo case is deferred; the
    24-hour token expiry plus the two implicit paths are sufficient for
    v1.
14. **User-initiated data export.** A *"download my data"* button that
    produces a machine-readable archive of the user's watchlists,
    libraries, ratings, completion dates, and shares is deferred. v1
    covers the right-to-erasure side (account deletion is irreversible
    and cascade-deletes everything) but does not surface a portability
    affordance. Revisit if real users ask for it or if a regulatory
    forcing function appears.
