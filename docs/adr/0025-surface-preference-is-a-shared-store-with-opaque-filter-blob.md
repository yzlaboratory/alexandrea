# Surface preference is a shared store, keyed by (user, surface, media_type), with an opaque filter blob

Sort and filter choices persist server-side per **`(user, surface, media_type)`**
(#3's spec) — and that key shape is not Catalog-specific. Watchlist (#7) and
Library (#4) will want the identical "remember my sort/filter" behavior on
their own Surfaces. Rather than let each surface grow its own preference
column, #3 builds one shared store now: a single table keyed by
`(user_id, surface, media_type)` holding a typed `sort_key` / `sort_direction`
pair plus a single opaque **JSON `filters` column**.

## Why shared

The four Surfaces (see `CONTEXT.md`) are symmetric in this respect — each is
independently stateful per media type, and "remember what I last chose" is
the same behavior in each case. Building four near-identical
`(user, media_type) → sort/filter` tables piecemeal, one per ticket, would be
three avoidable schema migrations and three copies of the same restore logic.

## Why the filters column is opaque JSON, not typed/validated columns

Sort is a uniform shape everywhere — one field plus a direction — so
`sort_key`/`sort_direction` are plain typed columns. Filters are not uniform:
Catalog filters are provider-delegated params drawn from ADR 0018's per-type
capability table (genre, original language, available-in language, runtime,
page count); Watchlist/Library filters will run against the locally
denormalized snapshot fields from ADR 0019 (`title`, `release_date`,
`genres`, `external_rating`) — a different vocabulary entirely, on a
different surface, validated against a different source of truth.

The shared store therefore does not need to understand what a filter
*means* — only to remember and hand back whatever filter map the calling
surface last gave it. It stores an opaque JSON blob (the same "heterogeneous
document in a SQLite text column" pattern already used for Rating's
Characteristics) and performs **no validation of filter keys or shape**.
Each surface's own service is responsible for validating the filters it
reads back against its own current capability table (e.g. #3 revalidates
against ADR 0018 on read, so a stale filter key from a since-changed genre
vocabulary is dropped rather than applied blindly).

## Considered and rejected

- **Four bespoke preference columns/tables, one per surface.** Rejected:
  three avoidable near-duplicate migrations and restore code paths for
  behavior that is identical in shape across all four Surfaces.
- **Typed, normalized filter columns** (`genre`, `original_language`,
  `runtime_min`, …) on the shared table. Rejected: the filter vocabulary
  differs by surface (provider-delegated params for Catalog vs. local
  snapshot fields for Watchlist/Library), so a fixed column set on a
  *shared* table would either serve only one surface's vocabulary or grow a
  superset of nullable columns that means something different depending on
  which surface wrote them.

## Consequences

- One table serves all four Surfaces; #7 and #4 read/write it without a
  schema change when their tickets land.
- The store cannot enforce "this filter key is valid for this media_type" —
  that check lives in each surface's service layer, on both write and
  read-back (capability tables can change over time, e.g. a future ADR
  0013 re-curation).
- Search is deliberately **not** part of this table — per #3's spec, search
  is transient and does not persist across sessions.
