# Data Model

## Why this exists

The other spec files describe scenarios and behaviors. This file pins down the **entities and relationships** they all reference, so the same word means the same thing everywhere. Without this, "watchlist," "title," and "rating" drift in subtle ways across specs, and the first inconsistency becomes a bug six months in.

This is not a database schema — those decisions live in a future ADR. Field types here are conceptual ("identifier," "timestamp"), not SQL.

## Conventions

- Every entity has an opaque `id` (server-generated) unless noted.
- Timestamps are stored in UTC; rendered in the user's locale.
- Foreign-key relationships are described in prose; the implementation may denormalize for read performance.

## Identity

### `User`

Represents a single person with an account.

- `id`, `email` (unique), `displayName`, `avatarColor` (or `avatarImageUrl`)
- `region` (e.g., `US`, `FR`) — drives availability and discovery rankings
- `locale` (e.g., `en-US`) — drives UI language and metadata language fallback
- `theme` (`light` | `dark` | `auto`)
- `createdAt`

### `AuthIdentity`

A user can sign in with multiple methods over time.

- `userId`, `provider` (`apple` | `google`), `providerSubjectId`, `email` (as returned by the provider)
- One `User` has 1..N `AuthIdentity` records.
- `User.email` is populated from the **first** attached identity's provider email and is not changed when a second identity is later linked. Apple "Hide My Email" relay addresses are stored as-is.

### `Session`

Active sign-in on a device.

- `id`, `userId`, `deviceLabel` (best-effort), `ipApprox`, `lastSeenAt`, `expiresAt`
- 90-day inactivity expiry.

## Sharing

### `SharedSpace`

A group of users who share lists and see each other's progress.

- `id`, `name` (e.g., `"Kira & M"`), `createdAt`, `archivedAt`

### `SharedSpaceMembership`

- `spaceId`, `userId`, `joinedAt`, `leftAt` (nullable)
- All members are equal; no roles in v0.
- A `User` can belong to 0..N `SharedSpace`s.

### `SpaceInvite`

- `id`, `spaceId`, `invitedEmail`, `createdByUserId`, `usedByUserId` (nullable), `revokedAt` (nullable)
- `reusable` (bool, default false), `maxUses` (default 1), `usesSoFar`

## Catalog (provider-sourced)

The catalog is derived from upstream providers (TMDB primary, Wikidata secondary; see ADR 0002). The internal entities below are our **own** representation — provider IDs are foreign keys, not the primary key.

### `Title`

A movie or a series.

- `id` (internal, opaque), `kind` (`movie` | `series`)
- External IDs: `tmdbId`, `imdbId` (nullable), `tvdbId` (nullable), `wikidataQid` (nullable)
- `originalTitle`, `originalLanguage`, `releaseYear`, `releaseDate`
- `runtimeMinutes` (nullable; movies only)
- `endYear` (nullable; series only)
- `popularityScore` (TMDB `popularity` field; used for the "Popular" rail in `03-discovery-browsing.md` — labeled honestly as engagement, not true watch counts)
- `voteAverage` (TMDB `vote_average`, 0–10), `voteCount` (TMDB `vote_count`) — the User Score per ADR 0003. Titles with `voteCount < 100` are shown as "Not enough ratings yet."
- `refreshedAt` (last metadata refresh from provider)

A `Title` always has at least one `TitleLocalization` row.

### `TitleLocalization`

Per-locale display data.

- `titleId`, `locale`
- `displayTitle`, `synopsis`
- `posterUrl`, `backdropUrl` (CDN-hosted; see `02-content-metadata.md` on caching)

### `TitleGenre`, `TitleContentWarning`, `TitleCertification`

- `titleId` plus the relevant tag/code value.
- `Certification` is region-scoped (`US:PG-13`, `FR:12`, etc.).
- `ContentWarning` is from a closed vocabulary: `violence`, `sexual`, `language`, `drugs`, `self_harm`, `flashing`.

### `Person`

- `id`, `tmdbId`, `name`, `profileImageUrl`

### `TitleCredit`

- `titleId`, `personId`, `role` (`director` | `creator` | `writer` | `cast`), `characterName` (nullable), `billingOrder`

### `Season` (series only)

- `id`, `titleId`, `seasonNumber`, `name`, `airDate`, `episodeCount`

### `Episode` (series only)

- `id`, `seasonId`, `episodeNumber`, `title`, `synopsis`
- `runtimeMinutes`, `airDate`
- `kind` (`regular` | `premiere` | `finale` | `special`)

### `StreamingService`

Closed reference table.

- `code` (e.g., `netflix`, `hulu`, `max`), `displayName`, `logoUrl`

### `StreamingAvailability`

Region- and service-specific availability for a title.

- `titleId`, `region`, `serviceCode`
- `kind` (`stream` | `rent` | `buy`)
- `deepLinkUrl` (nullable), `leavingAt` (nullable, for "leaving soon" indicator)
- `fetchedAt` (used to display staleness banner per `02-content-metadata.md`)

## User content

### `UserStreamingSubscription`

Which services a user has, in their region.

- `userId`, `serviceCode`, `region`

### `Watchlist`

A named, ordered collection of titles.

- `id`, `name`, `emoji` (nullable)
- `kind` (`personal` | `shared`)
- `ownerType` (`user` | `space`), `ownerId`
- `createdAt`, `archivedAt` (nullable)
- A user has at least one `personal` list (`"My List"`); a shared space has at least one `shared` list (`"Movie Night"`).

### `WatchlistEntry`

A title sitting in a list at a position.

- `id`, `watchlistId`, `titleId`
- `addedByUserId`, `addedAt`
- `position` (decimal — see "Ordering" below)
- `note` (single line, nullable)
- `removedAt` (nullable, supports 10-second undo and audit)

### `WatchlistEntryVeto` (shared lists only)

- `entryId`, `userId`, `vetoedAt`
- An entry with vetoes from **all** members of the space is auto-archived from the active list view.

### `WatchEvent`

A discrete watching action by one user.

- `id`, `userId`, `titleId`
- `episodeId` (nullable; null for movies)
- `watchedAt` (nullable for backfilled history)
- `backfilled` (bool)
- `groupContextSpaceId` (nullable; set when both members marked simultaneously, used for "watched together" stats)
- `createdAt`

A series' `TitleStatus` for a user is **derived** from their `WatchEvent` rows — not stored.

### `Rating`

A user's structured opinion of a watched title (or season, or series).

- `id`, `userId`, `titleId`
- `scope` (`movie` | `season` | `series`), `seasonId` (nullable, required when scope = `season`)
- Dimension scores (each 0–5 in 0.5 steps, nullable when skipped) per ADR 0006:
  `story`, `performance`, `vibe`, `entertainment`
- `overallScore` is **derived** as the arithmetic mean of the filled dimensions; not stored.
- `note` (single line, ~280 chars, nullable)
- `noteContainsSpoilers` (bool)
- `private` (bool — hides from partner)
- `firstRatedAt`, `lastEditedAt`
- `rewatchOf` (nullable, links to a prior `Rating` for the same title)

A `RatingEdit` audit row stores prior values per `07-ratings-and-reviews.md`.

### `AbandonReason`

Recorded when a user abandons a series.

- `userId`, `titleId`
- `reason` (`pacing` | `characters` | `too_heavy` | `lost_plot` | `lost_interest` | `other`)
- `customNote` (nullable), `occurredAt`

## Activity, async, and notifications

### `ActivityEvent` (per shared space)

Append-only feed for the shared-space activity panel.

- `spaceId`, `actorUserId`
- `kind` (`title_added` | `title_moved` | `title_removed` | `title_vetoed` | `marked_watched` | `rated`)
- `payload` (entity references)
- `occurredAt`

### `Notification`

In-app delivery record. v0 has no push or email notification channels (per `specs/00-overview.md`); fields for other channels will be re-introduced via migration when v1 adds native apps.

- `id`, `userId`, `kind`, `title`, `body`
- `deepLinkUrl`
- `sentAt`, `readAt`

### `NotificationPreferences`

- `userId`
- Per-kind in-app visibility toggles.
- `quietHoursStartLocal`, `quietHoursEndLocal` (suppresses in-app banners during the window).

## Derived / cached

### `HomepageSnapshot`

Pre-computed homepage payload per user, refreshed at most hourly.

- `userId`, `generatedAt`
- `tonightsPick` (titleId + reason text), `continueWatching[]`, `readyOnYourList[]`, `newForYou[]`

### `RecommendationSnapshot`

Cached personal recommendations per user (rebuilt on rating changes).

- `userId`, `generatedAt`, `rails[]`

## Ordering: how positions work in lists

`WatchlistEntry.position` is a **decimal** rather than an integer. To insert between two entries at positions `2.0` and `3.0`, the new entry gets `2.5`. This avoids cascading rewrites of every row when the user drags one item.

When positions get too dense (e.g., gaps under `0.0001`), a background job re-bases the list to evenly-spaced integers. Users never see this.

For shared lists, concurrent reorders use **last-write-wins per entry**: each `WatchlistEntry` carries a `positionUpdatedAt`, and the most recent write wins. This is acknowledged in `04-watchlists.md` to occasionally produce a surprising reorder, with a "list is changing rapidly" banner as the escape hatch.

> **Open question:** for true real-time collaborative ordering at scale, this naive approach is insufficient and would warrant a CRDT (e.g., fractional indexing with version vectors). Deferred — see "Follow-ups" below.

## Derived states

These are computed on read, never stored, and never persisted in user-visible form:

- **`TitleStatus(user, title)`** = `not_started` | `in_progress` | `finished` | `abandoned`, derived from `WatchEvent` rows + `AbandonReason`.
- **`SeasonProgress(user, season)`** = ratio of watched episodes to total.
- **`SeriesProgress(user, series)`** = aggregate of season progress.
- **`OverallRating(rating)`** = mean of filled dimension scores.
- **`SharedSpaceWatchedTogether(space, title)`** = true iff every active member has a `WatchEvent` for the title.
- **`AvailableNow(user, title)`** = exists `StreamingAvailability` matching any of the user's `UserStreamingSubscription` rows.

## Cardinality summary

- A `User` has 1..N `AuthIdentity`, 0..N `Session`.
- A `User` belongs to 0..N `SharedSpace` via `SharedSpaceMembership`.
- A `User` owns 1..N `Watchlist` (kind = personal); each `SharedSpace` owns 1..N `Watchlist` (kind = shared).
- A `Watchlist` has 0..N `WatchlistEntry`.
- A `Title` has 0..N `Season` (series), each with 1..N `Episode`.
- A `User` has 0..N `WatchEvent`, each pointing at a `Title` (and optionally an `Episode`).
- A `User` has 0..N `Rating`, at most one *current* `Rating` per `(user, title, scope, seasonId?)` — rewatches add new rows linked via `rewatchOf`.

## Open questions

- **"Popular" rail vs. true watch counts.** `Title.popularityScore` uses TMDB's `popularity` (engagement-weighted, not true watch counts). The product surface labels this honestly as "Popular" rather than "Most Watched." Acceptable for v0; revisit if a true watch-volume source becomes available.
- **Real-time collaborative ordering.** The decimal-position last-write-wins model handles the couch-with-two-people case under polling (ADR 0005). It would not handle high-concurrency editing if the user base ever grows. Revisit when a third concurrent writer becomes routine.
