# Watch Tracking

## Why this exists

The couple needs to mark what they've seen with the right granularity: a whole movie in one tap, a series at the episode level with seasons rolling up cleanly. Tracking has to feel like a reward ("nice, done with Season 2") rather than bookkeeping, which means the interactions after a watch should be fast — ideally two taps — and the system should do the rollup math for them.

## What gets tracked

For each user (not each couple):

- **Movie** — watched or not. Optional watch date.
- **Episode** — watched or not. Optional watch date.
- **Season** — derived from episode state: "not started," "in progress," "finished."
- **Series** — derived from season state: "not started," "in progress," "finished," "abandoned."

The derived states are what the UI shows most of the time. Users mark episodes; they don't usually mark seasons directly (though the bulk-mark affordance exists).

## The default watch-mark interaction

### Scenario: finishing a movie

Kira and M watch *Poor Things* together. When the credits roll, Kira opens the app, taps the shared list, taps *Poor Things*, taps *Mark watched*. A sheet slides up:

> **Poor Things** · watched today
> Mark watched for: [x] Kira  [x] M
> [Rate now] [Rate later]

Kira confirms. The title leaves "Movie Night" and moves to the shared history. Both users get a rating prompt (covered in `07-ratings-and-reviews.md`).

The key design point: **marking for the partner requires a single checkbox, not a second login.** The shared-space model trusts both members to mark on each other's behalf when watching together.

### Scenario: finishing an episode alone

Kira is on a flight and watches *The Bear* S3E4 by herself. She opens the app, navigates to the series, sees the episode list, taps S3E4. The episode marks watched for Kira only. M's row stays unchecked. The series detail page now shows "Kira: S3E4 · M: S2E10."

### Scenario: binge session

They watch three episodes of *Severance* in a row. After the third, Kira opens the app, long-presses S2E3, and picks "Mark S2E1–S2E3 as watched for both of us." Bulk-mark takes care of the previous two in one gesture. This is the third interaction pattern the app has to be good at — the first two are single-movie and single-episode.

## Watch state UI

On a series detail page:

- Each episode row has two small check indicators — one per member of the shared space, colored by their avatar color.
- A progress bar per season shows the couple's collective progress (lower bound = slowest partner).
- A "resume" CTA at the top picks up at the next unwatched episode *for the viewing user*.

On a movie in a list:

- A checkmark appears when watched, per member. If both, the movie auto-leaves the list.

## Partial states and correction

- **Undo** for at least 24 hours on any mark. A long-press on a watched item offers "Mark unwatched."
- **Abandoned.** A user can mark a series "abandoned." It leaves the active-tracking views but stays in history with that status. Abandonment is per-user: M can abandon *Yellowstone* while Kira keeps watching.
- **Backfill.** When a user adds a series they've already watched, they can tap "I've already seen this" and choose a season to mark everything up-to watched in one shot. Backfilled watches don't carry a specific date — they're stamped with a rough "before [today]" so stats know not to count them as recent activity.

## Watch events and history

Every mark creates a watch event with:

- User
- Title, season, episode (as applicable)
- Timestamp (or backfill flag)
- Whether it was part of a group viewing (both members marked simultaneously)

Watch events power the homepage's "what's next" logic, the history page, and the yearly stats view ("you watched 47 movies in 2025").

## Edge cases

- **A new episode airs of a finished series.** *The Bear* S3 drops a year after the user "finished" S2. The series moves back from "finished" to "new episodes" and surfaces on the homepage.
- **Rewatches.** Marking a movie watched that's already watched prompts: "You watched this on 2024-02-10. Log a rewatch, or cancel?" Rewatches add a second watch event without overwriting the first. Rating can be different per rewatch.
- **Specials and holiday episodes.** Tracked as episodes within their parent series. They don't block "finished" status — a user can finish *The Office* without watching every webisode.
- **Short-lived skipping.** If a user skips S2E3 and marks S2E4 watched, the app asks "Did you skip S2E3, or should I mark it watched too?" rather than silently leaving a hole.

## Cross-device sync

Watch marks sync across all of a user's devices within seconds when online. The model is last-write-wins per-event, but because watch events are additive (and per-user), conflicts are rare:

- Marking the same episode watched twice collapses to a single event (dedup by user + episode + day).
- Unmarking an episode on one device while marking it on another resolves in favor of the most recent action; the user sees a subtle "synced from your iPhone" indicator.

## Offline behavior

- The full user library, ratings, and watch history are cached locally and render offline.
- Marks made offline are queued and flushed on reconnect. The user sees a small badge ("2 pending") in the corner of the screen until sync completes.
- Conflicts on flush are resolved silently unless user-visible (rare); when visible, a single toast explains what changed.

## Spoiler safety

By default:

- Episode synopses are hidden (behind a tap-to-reveal) for episodes the user hasn't watched if the series is currently airing and the user is mid-season.
- Episode thumbnails (which often spoil plot points) are blurred until tapped, same conditions.
- The user can disable spoiler-safe mode globally in Settings.

Partner progress indicators (M's check marks) never reveal plot content — only whether they've watched.

## Accessibility of tracking

- Episode lists are navigable by keyboard (desktop) and by VoiceOver/TalkBack (mobile), with clear "watched / unwatched" state announced.
- Bulk-mark and undo are reachable without gestures — every gesture has a tap-only fallback in the long-press menu.

## Deliberately out of scope

- Automatic detection from streaming services. The user marks manually; there's no Netflix-account-link.
- Playback inside the app.
- Community watch-along features (syncing with people outside the shared space).
