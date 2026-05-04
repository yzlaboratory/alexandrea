# A TV entry is the whole series, not a season or episode

For TV, a single `Catalog Item` is the **entire series** (TMDB
`/tv/{id}`). Seasons and episodes are not first-class entries in v1 —
they are below the granularity of the Watchlist and Library.

Completing a TV entry means "I am done with this show, in my own
judgement." That covers both finishing through to the finale and
abandoning a show partway with an opinion worth recording. There is
no "currently watching" state — the Watchlist holds shows the user
intends to start, the Library holds shows the user has drawn a
personal line under. Re-completion of a series produces one new
**Completion Date** for the whole series, regardless of how many
episodes were re-watched.

Ongoing shows (still airing upstream) may be Completed at any time.
If a future season drops and the user re-watches, that is a normal
re-completion: another Completion Date is appended and the Rating
is overwritten wholesale per `complete-entry.md`.

## Why not per-season?

- The Rating shape would multiply by ~6× for a typical series. The
  per-aspect Characteristics in v1 (Story / Acting / Music / Visuals
  / Direction / Overall Enjoyment) are designed to score one
  authored work, not a season-by-season variance curve.
- Watchlists and Libraries would balloon for any user who watches
  long-running shows. Browse and share semantics get noisier.
- TMDB exposes both `/tv/{id}` and per-season endpoints, but the
  series id is the natural unit a person refers to ("I watched
  Severance"), and it matches how Movies / Books / Games already
  work in the schema.

## Accepted cost

A user who feels strongly that "S1 was 10/10, S5 was 4/10" cannot
record that nuance — they get one Rating for the whole series. If
this pattern is worth supporting later, per-season entries would be
a deliberate feature with a clear data-model change, captured in
`OOS.md` first and graduated into a spec only when the use case is
real. It must not be added by drift.
