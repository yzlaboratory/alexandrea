# Ratings

After a title is marked `watched`, either user can leave a rating.

## Shape

- **Score** — integer 0–5. Required.
- **Note** — single line, ~200 characters. Optional.
- **Rater** — which of the two users left it.

A title can have zero, one, or two ratings (one per user). Ratings are editable and overwrite in place; no audit log, no history.

## Display

On the title page, both users' ratings are shown side by side with the rater's display name.

On the library's `watched` tab, the title shows:

- the average of the two scores if both users rated, or
- the lone score if only one user rated, or
- nothing if neither has rated yet.

## Rules

- Rating a title that isn't in the library is not allowed — add it first.
- Rating a title that isn't in `watched` status is allowed; rating implicitly transitions the entry to `watched` if it was `want`, `watching`, or `abandoned`.
- A user can delete their own rating. Deletion removes the row entirely.

## Deliberately out of scope

- Multi-dimension ratings (story / performance / vibe / entertainment)
- Private ratings hidden from the partner
- Spoiler toggles on notes
- Ratings on re-watches (a second watch overwrites; we do not keep rewatch history)
- Rating seasons or episodes (the whole title is the only unit)
- Audit rows for edits
