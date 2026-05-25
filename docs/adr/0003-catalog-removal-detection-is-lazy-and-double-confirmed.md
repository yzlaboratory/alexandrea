# Catalog removal detection is lazy and double-confirmed

> **Status: Superseded by ADR 0009.** The cascade-on-double-
> confirmation mechanism described below was retracted: upstream
> removal no longer destroys local rows, so the double-confirmation
> guard it provided is no longer needed. The four normalised
> adapter outcomes (`present`, `removed`, `redirected_to`,
> `transient_failure`) survive in ADR 0009 with different actions
> attached. Read this ADR for historical rationale only.

The cascade behaviour committed to in ADR 0001 needs a detection
mechanism. We do not run a periodic sweep against the three upstream
providers (TMDB, OpenLibrary, IGDB). Instead, removal is detected
**lazily, on access**, and only fires the cascade after **two
independent confirmations** of removal — never on a single signal.

## Mechanism

Each upstream is wrapped in a provider adapter that normalises the
raw response into one of four outcomes:

- `present` — entity exists; render normally
- `removed` — entity is gone (TMDB `404`, OpenLibrary `404`,
  IGDB `200 OK` with empty body)
- `redirected_to: <new_external_id>` — OpenLibrary `301`; this
  is **not** a removal, see below
- `transient_failure` — anything else (auth errors, rate limits,
  5xx, network failures, no response)

A cascade fires only when an entity returns `removed` **twice** —
once on the request that surfaced it, then again on a follow-up
fetch (either later in the same request flow or on the next access
to the same Catalog Item). A single `removed` marks the local row
as `pending_removal` and renders the entry with the same
"currently unavailable" UI used for the existing
`External catalog is unreachable` scenario in #3.
This guards against a single TMDB hiccup nuking a user's library.

## OpenLibrary merges (301) are migrations, not removals

OpenLibrary routinely merges duplicate records, redirecting the
losing id to the winner via HTTP 301. We treat this as a silent
**migration**: the local Catalog Item's `external_id` is updated to
the new value and the entry is re-fetched. No cascade, no user
notification. This matches the user's mental model — the book is
the same book, just with a tidier id upstream.

## Consequences

- **No background job, no provider rate-limit budget for sweeps.**
  Detection runs only when the user pays attention to an entry.
- **Long-tail entries can reference dead upstream ids indefinitely**
  until the user views them. We accept this — there is no cost
  while no one looks.
- **A real removal takes two visits to disappear.** Acceptable
  trade-off against the cost of false-positive data loss.
- **The cascade rule is per-adapter outcome, not per-HTTP-status.**
  Anyone changing a provider integration must update the adapter to
  emit the same four normalised outcomes.
