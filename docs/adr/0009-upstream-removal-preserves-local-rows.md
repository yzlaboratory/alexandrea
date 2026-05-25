# Upstream removal preserves local rows and surfaces a "removed by <provider>" affordance

**Supersedes ADR 0003.** Amends ADR 0001 — the cascade-delete
behaviour described there is replaced by the rule below.

When an upstream provider returns a clean `removed` outcome on a
**fresh fetch** (i.e. a cache miss after the 7-day TTL of ADR 0007
expires), the local row is **not deleted** and **no cascade fires**.
Instead, the entry's display flips to a per-provider **"removed by
<provider>"** affordance — "Removed by TMDB" for Movies/TV,
"Removed by OpenLibrary" for Books, "Removed by IGDB" for Games —
in the grid tile and in the detail overlay. The user's local data
(Watchlist or Library membership, Rating, every Completion Date)
is preserved exactly as it was.

The user can manually delete the entry from their Library or remove
it from their Watchlist if they no longer want it; those paths are
already covered by #5 and #7.

## Provider adapter outcomes

Each upstream is wrapped in a provider adapter that normalises the
raw response into one of four outcomes:

- `present` — render normally.
- `removed` — render the "removed by <provider>" affordance. Local
  row, Rating, and Completion Dates are preserved.
- `redirected_to: <new_external_id>` — OpenLibrary `301` and
  equivalents. Treated as a silent **migration**: update the local
  Catalog Item's external_id and re-fetch. No affordance change,
  no user notification.
- `transient_failure` — render the existing "currently unavailable"
  transient UI from #3. Do **not** promote a
  transient failure to `removed`. A 503, an auth blip, or a
  network timeout must never flip a user's display to "removed by
  <provider>."

## Why no double-confirmation, no `pending_removal`

The previous ADR 0003 added a two-confirmation rule and a
`pending_removal` intermediate state specifically to guard against
a single upstream hiccup nuking a user's library. With the new rule
the hiccup risk evaporates: a `removed` outcome no longer triggers
a destructive action, only a display flip. The display is fully
reversible — if the provider re-adds the entry, the row reverts to
`present` automatically on the next fresh fetch with no manual
cleanup. There is nothing to confirm, so the confirmation rule is
dropped along with the intermediate state.

## Recovery on upstream restoration

If a `removed` row's next fresh fetch returns `present` (e.g. TMDB
un-deletes a film, or what looked like a 404 was a transient
mis-routing missed by the adapter), the affordance disappears and
the row renders normally on the user's next access. No background
job is required and no user action is needed.

## Consequences

- **No data is ever destroyed by upstream removal.** A user who
  invested a Rating into an entry keeps it forever, even if the
  provider later disowns the catalog item. This is the headline
  change versus ADR 0001's original cascade.
- **Filters and sorts that depend on upstream metadata exclude
  removed entries.** Genre filter has no genre to match; release-
  date sort has no release date; title sort has no title;
  external-rating sort has no rating. This matches the null-
  honesty rule of ADR 0006.
- **Filters and sorts that depend on local data still include
  removed entries.** Overall Enjoyment range, completion year, and
  Completion Date sort all work — the user can still find their
  removed entries via their own Rating and Completion Dates.
- **Library and Watchlist may grow without bound** if a user
  never manually deletes removed entries. We accept this —
  storage is cheap, and the user has agency to clean up.
- **A Share view shows removed entries** if they qualify under
  the Share's filter on local data, rendered with the same
  removed affordance the Owner sees.
- **The local row's identity is `(external_provider, external_id,
  media_type)`** — and that identity is now durable forever once
  saved, even if the upstream id no longer resolves.
