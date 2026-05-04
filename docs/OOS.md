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
