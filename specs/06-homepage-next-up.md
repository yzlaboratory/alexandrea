# Homepage and "Next Up"

## Why this exists

The homepage is the answer to the question Kira asks most often: *"What should I watch next?"* It should answer without making her think, and it should answer differently at 8pm on a Saturday with M on the couch than at 11pm on a Tuesday when she's alone and tired. The homepage is not a feed to scroll; it is a decision surface.

## The shape of the homepage

When Kira opens the app, the first screen she sees is her personal homepage, top to bottom:

1. **Tonight's pick** — one bold recommendation with a poster, title, why-it's-suggested label, and a *Start watching* CTA.
2. **Continue watching** — series she's mid-way through, ordered by most recent activity.
3. **Ready on your list** — titles from her watchlists that are currently streamable on a service she has.
4. **New for you** — new episodes of series she's following, plus releases of titles on her list.
5. **With M tonight?** — a mini-section visible only when the user has toggled **Couch mode** (a manual signal: "I'm watching with M right now"); surfaces shared-list picks and agreed-on next-up episodes. v0 has no automatic presence detection (per ADR 0005).

The homepage is deliberately shorter than Discover. If the user wants to browse, they go to Discover. If they want to *decide*, they stay here.

## "Tonight's pick" logic

A single hero recommendation, generated per user, refreshed once per day (or on pull-to-refresh). The logic, in rough priority order:

1. If there's an unfinished episode in a series the user watched within the last 7 days → suggest the next episode.
2. Else if a movie on their watchlist is leaving a subscribed service within 14 days → suggest that.
3. Else if a shared-list title is newly available on a service both partners have → suggest that.
4. Else pick a top-rated title from their list matching the current time-of-day profile (shorter on weekdays, longer on weekends).

The "why" label under the pick makes the reasoning visible: *"You're 4 episodes into Severance Season 2."* or *"Leaves Netflix in 9 days."* — this builds trust in the recommendation.

### Scenario: Tuesday night, alone

Kira opens the app on Tuesday at 10:15pm. Tonight's pick is *Severance* S2E5, labeled *"Next in the series you're watching."* Runtime shown: 48 minutes. She taps *Start watching*, which marks her intent (not the watch itself) and opens the streaming service deep link.

### Scenario: Saturday night, together

Kira opens the app at 8pm Saturday and toggles **Couch mode** in the header. The homepage adapts: Tonight's pick is *Challengers*, labeled *"Top of your Movie Night list, streaming on Hulu."* Runtime: 2h 11m. Below it, the "With M tonight?" section shows two alternates from the shared list in case they want to swap.

## "Continue watching"

A horizontal row of series cards, each showing:

- Poster
- Series title
- Next episode ("S3E5 — *Children*")
- How long since last episode watched ("3 days ago" → starts feeling stale at 2+ weeks)

If a series has been stale for >30 days, it fades visually, with a *"Still watching?"* affordance that either resumes it or marks it abandoned.

## "Ready on your list"

Titles from all of the user's watchlists (personal + shared) currently streamable on one of their services, ranked by:

1. Leaving-soon status (most urgent first)
2. List priority (top-of-list first)
3. TMDB User Score as tiebreaker

This section exists because the biggest frustration with a watchlist is forgetting that the thing you want *is available right now.*

## "New for you"

- New episodes airing this week for followed series
- New seasons that dropped since last open
- Release-date arrivals of watchlisted movies (e.g., a watchlisted theatrical release now streaming)

Each item has a context tag: *"S3E1 aired Tuesday," "New season dropped," "Now on Max."*

## "With M tonight?" (shared surface)

Renders only when the user has toggled **Couch mode** — a manual signal in the header that says "I'm watching with M right now." v0 has no automatic presence detection (the polling model in ADR 0005 doesn't carry presence information). Couch mode auto-clears after 4 hours, or on explicit dismiss.

Contents:
- Top of the shared "Movie Night" list, filtered to streamable-right-now
- Any episode of a shared series where both partners are at the same unwatched index
- A *"Surprise me"* button that picks a random top-rated shared-list item

## Empty states

- New user with no watch history: Tonight's pick becomes *"Start by adding 5 movies to your list"* with a direct jump to Discover.
- User with a list but nothing streamable: Tonight's pick explains the gap honestly — *"Nothing on your list is streamable on your services tonight"* — and offers a discovery rail below.

## Personalization signals

- What the user has watched (genres, runtime preferences, directors)
- What they've rated highly (not just watched)
- Time-of-day patterns (shorter picks on weeknights)
- Partner overlap (titles both partners have shown interest in)

Personalization is tuned toward *confidence over coverage* — better to recommend one thing well than five things mediocrely.

## Refresh behavior

- **Pull-to-refresh** on mobile and a refresh button on the desktop regenerate Tonight's pick and re-sync all sections.
- The homepage auto-refreshes in the background at most once per hour to avoid thrashing the backend. A small "updated 12 min ago" timestamp is visible in the header.

## First-run onboarding

A new user's first homepage experience is a three-step lightweight onboarding:

1. **Pick your streaming services** (checkboxes with logos). Powers availability filtering.
2. **Add at least 5 titles** you want to watch. A search bar and a starter grid of top-rated recent titles sits below.
3. **Invite someone to watch with?** Optional. Defaults to "Not now."

Onboarding is skippable at every step; a skipped user lands on a homepage tuned for exploration (Discover front and center) until they populate their library.

## In-app indicators around the homepage

v0 is web-only and has **no push notifications, no email digests** (per `00-overview.md` and ADR 0007). Instead, the homepage surfaces these signals as in-app indicators visible on next open:

- A small "new" badge on a "Ready on your list" entry when a shared-list title became streamable since last open.
- A "new episode" badge on Continue Watching cards when a followed series has aired since last open.
- All badges clear on view; refresh on app open via polling (ADR 0005).

## Loading, empty, and error states

- **Loading:** the poster for Tonight's pick renders as a skeleton; other rails render as shimmer placeholders.
- **Empty homepage (new account):** onboarding above takes over the full screen.
- **Network error:** the cached last-known homepage renders with a yellow banner "Offline — showing last-synced view." Actions that require the network disable gracefully.

## Accessibility

- Tonight's pick is the first focus target when the page loads (keyboard and screen reader).
- Every rail has a descriptive heading announced by screen readers ("Continue watching, 4 items").
- The Couch mode toggle is reachable by keyboard and announced by screen readers as a toggle button with its current state.

## Deliberately out of scope

- Push notifications pestering the user to watch something. The app is opened when the user is ready.
- Recommendations sourced from strangers or public trend data beyond what's already in Discover.
- A full "for you" infinite feed. The homepage is a handful of decisions, not a firehose.
