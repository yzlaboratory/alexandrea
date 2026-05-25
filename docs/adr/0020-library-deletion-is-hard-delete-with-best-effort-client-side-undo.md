# Library deletion is a hard delete; Undo is a best-effort client-side restore

Deleting a Library entry (#5) **hard-deletes** the row, all its Completion
Dates, and its Rating immediately and server-side — the delete is genuinely
permanent, as the spec promises. The DELETE response returns the **full
deleted payload** (the catalog reference, the ADR 0019 snapshot, the Rating,
and every Completion Date). The client holds that payload for the lifetime of
the undo toast (5 seconds) and, on **Undo**, re-creates the entry by sending
the payload back — restoring it in **Library** state. There is no server-side
tombstone and no grace-period sweep.

## Why not a soft delete

A soft delete (tombstone the row, hide it, sweep it later) would survive a
page reload during the undo window, but it costs a tombstone state on every
row, a cleanup job (ADR 0017 territory), and a window where a row exists but
is hidden — three things a future engineer can get wrong. Hard delete keeps
the data model honest: "permanent" and "discards all Completion Dates and the
Rating" are literally true the instant the delete returns.

## Consequences

- **Undo is best-effort.** Reloading, navigating away, or closing the tab
  inside the 5-second window loses the ability to undo — but the toast is gone
  by then too, so the loss is invisible to the user.
- **DELETE returns a body, not a bare 204.** The endpoint must hand back the
  full entry payload so the client can reconstruct it.
- **Undo is a normal re-create** (a fresh row id) that restores the Rating,
  every Completion Date, and the snapshot. The one-way lifecycle holds — undo
  restores to the Library, never to the Watchlist.
- **Removed-upstream entries (ADR 0009) delete and restore identically.** The
  payload carries the last-known snapshot (ADR 0019), so undo works even when
  the upstream no longer serves the title.
- **Double delete is safe.** A second DELETE of an already-deleted entry is a
  no-op; the unique constraint (one row per `(user, Catalog Item, media_type)`)
  keeps a re-create single even if two tabs race an undo.

## Watchlist removal uses the same shape

Removing an entry from the **Watchlist** (#7) is the same hard-delete +
best-effort client-side restore, with two differences: there is no Rating or
Completion Date to discard, and Undo restores the row to **Watchlist** state.
The returned payload includes the original **`date added`** (alongside the
ADR 0019 snapshot), so Undo re-creates the row in its previous position under
every sort — without it, a fresh `date added` would bounce the entry to the
top of the date-added sort.
