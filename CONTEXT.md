# Entertainment Library

A personal, per-media-type tracker for things a user plans to consume
(**watchlist**) and things they have consumed and rated (**library**).
External catalogs (TMDB, OpenLibrary, IGDB) supply discovery; the
user's own watchlist, library, ratings, and shares are local.

## Language

### Catalog

**Catalog Item**:
A title that lives in an external catalog (TMDB / OpenLibrary / IGDB).
Identified locally by `(external_provider, external_id, media_type)`.
Title, cover, release date, genres, external rating — none of these
are stored locally; they are fetched live from the upstream provider
through a cache layer (see ADR 0001).
_Avoid_: media item, title record.

**External ID**:
The provider-issued identifier for a Catalog Item (e.g. a TMDB id).
The only piece of catalog metadata our database holds.

**TV entry granularity**:
For TV, the Catalog Item is the **entire series** (TMDB `/tv/{id}`).
Seasons and episodes are not first-class — there is no per-season
Watchlist or Library row, and one Rating covers the whole series. See
ADR 0005.

### Lifecycle verbs

**Complete**:
The single canonical verb for finishing an entry — applies to all four
media types. The UI label varies by media type ("Mark as watched" for
Movies/TV, "Mark as read" for Books, "Mark as played" for Games), but
the domain term, the action, and the stored timestamp are always the
same.
_Avoid (in domain/code)_: watch, finish, consume, mark-as-watched.

**Completion Date**:
The date on which an entry was Completed. Stored as a `YYYY-MM-DD`
calendar date with no time-of-day component. "Today" is computed in
the **user's browser timezone** at submission time — not server UTC,
not an account-locked timezone — so a user who clicks "Mark as
watched" at 9pm local on May 4 always gets `2026-05-04` regardless
of where the server lives. An entry may have many Completion Dates
(re-watches/re-reads/re-plays), and same-day re-completions append a
duplicate of today's date; the **Rating** attached to the entry is
single and is overwritten on each Completion.

### Rating

**Rating**:
A JSON document of one-or-more **Characteristics**, each scored 1–10.
Exactly one Rating per library entry; re-completing an entry replaces
the whole document. Genre is irrelevant to Rating shape — every entry
of a given media type is offered the same form, and the user fills in
whichever Characteristics they care to score (subject to one required
Characteristic, below).
_Avoid_: review, score, scorecard.

**Characteristic**:
A single named axis within a Rating (e.g. Story, Acting, Music,
Visuals, Direction, Overall Enjoyment). **Overall Enjoyment is the only
required Characteristic** and is present on every Rating, regardless of
media type. All other Characteristics are optional. Filters and sorts
operate per Characteristic and **exclude** entries whose Rating does
not contain that Characteristic — an entry without a Story score is
invisible to "Story ≥ 8" regardless of why it's missing. Filtering or
sorting by Overall Enjoyment never excludes anything because every
Rating has it.

**Overall Enjoyment**:
The single required Characteristic on every Rating, scored 1–10.
Universal across media types; serves as the canonical "how good was it
to me" axis for sort and filter when no per-aspect Characteristic is
specified.

### Identity & sharing

**User**:
An identity that owns exactly one Watchlist and one Library per
media type. Identity itself — email, password, sessions, verification
state — is owned by **kiraauth** (a separate backend service); within
the entertainment library a User is identified only by the opaque
**kiraauth User ID**, stored as a foreign key on every per-user row.
The library never holds an email address, a password, or a session
token. See ADR 0014 for the integration shape and ADR 0016 for what
the library does when kiraauth notifies it of a User deletion.
_Avoid_: account, member, customer.

**Owner**:
A User in the role of having created a Share for one of their own
Libraries. Not a separate identity — just a role-name used in the
sharing spec.

**Share**:
An unguessable URL that exposes a filtered, sorted, read-only view of
**one media type's Library** to whoever opens it. Carries a captured
`(filters, sort)` snapshot and an optional expiry; immutable after
creation. The URL token is the only credential.

**Friend**:
Anyone who opens a Share URL. **No first-class identity** — the URL
holder is the friend. The share's *content* is identical for all
openers (anonymous visitors and logged-in Users alike); a logged-in
opener additionally sees:

- two **cross-actions** per row — *add to my watchlist* and
  *complete-and-rate now* — that operate on their own Watchlist /
  Library, exactly as those actions work in the catalog;
- per-row **status badges** when the opener has already touched the
  entry (already on my watchlist / already in my library, with my
  own rating shown alongside the owner's);
- the **Uncompleted filter** (see below).

When the opener is the Owner of the share they are viewing, they see
the same anonymous content plus a "this is your shared view" notice
and a link to the **Shares** tab for this media type for revoking it.

**Uncompleted (filter)**:
A share-view-only filter, available to a logged-in Friend, that hides
any entry the opener has already Completed (i.e. that already lives in
the opener's own Library for this media type). Its purpose is "show me
the owner's picks I haven't seen yet".

## Relationships

- A **Watchlist** holds entries the user has not yet **Completed**
- Completing an entry moves it from the **Watchlist** to the **Library**
- The lifecycle is **one-way**: a Library entry cannot be moved back
  to the Watchlist. The only way to leave the Library is to **delete**
  it, which is permanent and discards all Completion Dates and the
  Rating; the underlying Catalog Item is rediscovered only through
  catalog browse
- A **Library** entry has one or more **Completion Dates** and exactly
  one current **Rating**
- A **Rating** has one or more **Characteristics**
- Every local entry references exactly one **Catalog Item** by
  `(external_provider, external_id, media_type)`; if the upstream
  provider removes the Catalog Item, the local entry is **preserved**
  (with its Rating and Completion Dates) and rendered with a
  "removed by &lt;provider&gt;" affordance — see ADR 0009

## Flagged ambiguities

- "watched" was used universally across all media types — resolved:
  the canonical domain verb is **Complete**; the UI varies the label
  by media type only.
- "entry" was used to mean a Catalog Item, a Watchlist entry, and a
  Library entry interchangeably — resolved: there is one local row
  per `(user, Catalog Item)`; its state determines whether it appears
  on the Watchlist, in the Library, or both is impossible (Completing
  removes it from the Watchlist).
