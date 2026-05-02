# The Shared Library

There is exactly **one** library, shared by both users. There are no personal lists, no multiple lists, no invite flow.

## Entries

A **library entry** links one title to a status.

Statuses:

- **want** — added to the library but not started.
- **watching** — currently in progress.
- **watched** — finished.
- **abandoned** — started and given up on.

A title appears in the library at most once. Changing status updates the existing entry; it does not create a new row.

## Actions

From the library view, either user can:

- **Add a title** — search → pick → entry is created with status `want`.
- **Change status** — four buttons or a dropdown on the entry.
- **Remove a title** — immediate, no undo, no soft delete.

From a title page, the same actions plus adding or editing a rating (see `03-ratings.md`).

## Views

The default view is the library filtered to `want` + `watching` — the "what's on our plate right now" surface.

Separate tabs show `watched` and `abandoned`.

Filtering by movie vs. series is out of scope for the MVP.

## Collaboration

Both users see the same library. A change by one is visible to the other on next page load. There is no real-time sync, no activity feed, no "who changed what", no undo.

## Deliberately out of scope

- Ordering / drag-reorder
- Multiple lists (personal or otherwise)
- Episode-level tracking
- Shared-space invite flow
- Activity feed
- Vetoes
- Undo
- Separate "added by" attribution per entry
