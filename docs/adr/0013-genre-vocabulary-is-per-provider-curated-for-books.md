# Genre vocabulary is per-provider for Movies/TV/Games and curated-mapped for Books

The "Filter by genre" affordance on Catalog, Watchlist, and Library
is a finite list of named genres per media type. The list **per
media type** is built differently because the upstream providers
expose genre very differently:

- **Movies (TMDB)** — use TMDB's native genre enum directly
  (Action, Comedy, Drama, …; ~19 values).
- **TV (TMDB)** — use TMDB's TV genre enum directly (Action &
  Adventure, Animation, …; ~16 values).
- **Games (IGDB)** — use IGDB's native genre enum directly
  (Shooter, Platform, RPG, …; ~23 values).
- **Books (OpenLibrary)** — use a **curated app-owned enum** of
  ~15 high-level categories mapped to OpenLibrary `subjects` via
  a maintained alias table.

The Books curated v1 list is approximately:

> Fiction (general), Science Fiction, Fantasy, Mystery & Thriller,
> Romance, Horror, Historical Fiction, Literary Fiction, Young
> Adult, Children's, Biography & Memoir, History, Science & Nature,
> Philosophy & Religion, Reference & Other.

Each curated value owns a list of OpenLibrary subject strings it
matches (e.g. *Science Fiction* matches `Science fiction`, `Sci-Fi`,
`Science-fiction`, `Speculative fiction`). A book matches a curated
genre iff **any** of its OpenLibrary subjects matches **any** of
that genre's aliases, case-insensitive. A book that matches multiple
curated genres (most do) shows up under each — the filter is
"matches genre G," not "is exclusively genre G."

The mapping table is a maintained file in the repo. Adding a
curated value or expanding aliases is a small PR, not a schema
migration. The list is intentionally short — readability and
filter-chip usability beat exhaustive coverage.

## Why curated for Books and not for the others

OpenLibrary `subjects` are user-contributed, free-form, multi-
language, frequently duplicative (`Science fiction`, `Science
Fiction`, `Sci-Fi`), often hyper-specific (`American literature
20th century`), and contain noise (`Accessible book`, `Protected
DAISY`). The total cardinality is in the hundreds of thousands.
A "pick from the dropdown" UI built on this directly is unusable;
"only show subjects matching current results" is unstable and
surfaces noise; surfacing all subjects is absurd.

TMDB and IGDB do the curation work themselves — their genre enums
are short, stable, and editorially-controlled. We pass those
through directly without intermediation. Curating a Movies genre
list of our own would only divorce our filter from the provider's
own data with no benefit.

## Why not unify genres across media types

A unified cross-media-type genre vocabulary (one "Drama" filter
that hits Movies, TV, and Books at once) is the seductive
alternative. We rejected it because:

- Per ADR 0004 every list is per-media-type in v1 — there is no
  cross-type surface where a unified vocabulary could be applied.
- Each provider's "Drama" means something different and overlaps
  imperfectly. Forcing a common denominator either loses fidelity
  (reduce TMDB's 19 down to a shared ~10) or invents categories
  that don't map cleanly (Games has no "Drama").
- The user's mental model when browsing Movies is TMDB's category
  set, not an app-invented one. Surfacing TMDB's enum directly
  matches that model.

## Why not drop the Books genre filter entirely

Genre is one of the most-asked filters in any media tracker.
Dropping it for Books would be a notable functional regression
versus Movies/TV/Games. The curated list is small, manageable,
and recoverable — and ADR 0006-style null honesty already covers
"book has no matching subject" (it just doesn't match the filter,
no implicit "Other" bucket needed).

## Why not free-text search over subjects

A text input where users type a subject substring (`mystery`,
`fantasy`) feels flexible but is a worse UX:

- Filter chips show *what's filtered*. A free-text input doesn't
  produce a chip the user can see and remove easily.
- Persistence per (user, surface, media_type) becomes
  awkward — a typed string is a vague restored state.
- Sharing semantics get harder — a captured filter (per
  #1) wants enumerable values to display as
  a pill on the Friend's Share view.

Curated genres slot cleanly into the existing chip-filter system.

## Consequences

- **A maintained alias file lives in the repo.** Books genre
  coverage is only as good as that file. Coverage gaps surface as
  "this book seems like Sci-Fi but the filter doesn't catch it"
  user reports; the response is to extend an alias list, not to
  redesign the system.
- **The detail overlay still shows raw upstream subjects** for
  Books (per #6's "subjects or genres" line).
  Curation is a filter-input concern, not a display concern —
  users see what OpenLibrary actually has.
- **Curated genres are stable v1 contracts.** Removing a curated
  value (e.g. dropping "Reference & Other") would invalidate a
  Share whose captured filter named it. Any future curated-list
  trim must consider live Shares (likely just rendering an
  inactive chip with the old name on those views).
- **A book filtered by, say, "Fantasy" can also match
  "Young Adult" and "Children's"** simultaneously and appear
  under all three filter values across separate filter
  applications. That is the desired behaviour.
- **Switching media types resets the genre filter** because
  filters persist per (user, surface, media_type) — already the
  rule. The vocabularies are not interconvertible.
