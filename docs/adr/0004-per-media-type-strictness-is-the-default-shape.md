# Per-media-type strictness is the default shape

Every list, every action, every share, and every Rating in v1 is
scoped to **exactly one media type at a time** — Movies, TV, Books,
or Games. There is no unified "everything I want to consume" or
"everything I have rated" surface; switching media type is an
explicit navigation step. The deferral of unified views is recorded
in the deferred-items backlog (#9).

We chose strict-per-type because:

- Each upstream provider speaks **one** media type. The data
  vocabulary, search shapes, and rate-limit budgets all differ;
  forcing a unified surface forces an awkward common denominator.
- The Rating shape differs per type (six Characteristics for
  Movies/TV, one for Books/Games in v1) — a cross-type list either
  hides per-aspect detail or shows a ragged form.
- Share semantics stay simple: one URL captures one type's filters.
  A cross-type share would have to compose multiple filter shapes.
- Per-surface list size is bounded by what one type can produce,
  which keeps infinite-scroll tractable.

The accepted cost: the user must switch type to compare across
types. We judge this acceptable for v1 — the use cases for
cross-type browsing are weak, and the deferred-items backlog (#9) preserves the option.

This ADR exists so a future engineer does not quietly unify the
surfaces under "consistency". Unifying is a real refactor (a
unified-view layer over per-type stores) and should be a deliberate
decision, not drift.
