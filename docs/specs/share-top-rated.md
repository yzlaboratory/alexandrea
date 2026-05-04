# Share a filtered subset of my library with a friend

The library **Owner** generates an unguessable URL — a **Share** — that
exposes a filtered, sorted subset of one media type's Library. A
**Friend** opens the link in any browser. Per ADR 0004, every Share
covers exactly one media type. Shares are **immutable** (to change
anything, revoke and create a new one) and **live** (they reflect the
current state of the Owner's Library every time the Friend loads the
page). Default expiry is 30 days, with an opt-out at creation. Revoked
and expired Shares stay resolvable by URL (Friends see a terminal
message), but they are **hidden from the Owner's management surface** —
the per-media-type **Shares** tab shows only active Shares (see
`manage-shares.md`). Per-Owner rate limiting and share usage
observability are deferred (`OOS.md` items 9 and 10).

The Share view's **content** is the same for every opener (anonymous
visitor, logged-in User, even the Owner of the Share). What differs is
the **affordance overlay**: a logged-in opener additionally sees
cross-actions and per-row status badges; the Owner sees a banner
naming the Share as their own and a link to the Shares tab for this
media type.

```gherkin
Feature: Share a filtered subset of my library with a friend
  As a library Owner
  I want to share a filtered, sorted subset of my library via an unguessable URL
  So that a friend can browse my picks without seeing my whole library or signing in

  Background:
    Given I am logged in
    And I have selected a media type
    And I am viewing my library for this media type

  Scenario: Generate a Share with the default 30-day expiry
    Given I have applied filters and a sort to my library
    When I create a Share without changing the default expiry
    Then a new unguessable Share URL is generated
    And the Share captures the exact (filters, sort) snapshot active when I created it
    And the Share is set to expire 30 days from now
    And the Share appears on my Shares tab for this media type with its expiry date
    And a toast confirms the creation and offers a "Copy link" affordance

  Scenario: Generate a Share with no expiry
    Given I have applied filters and a sort to my library
    When I create a Share and disable the expiry option
    Then a new unguessable Share URL is generated with no expiry date
    And the Share appears on my Shares tab for this media type marked as never-expiring

  Scenario: A Share is immutable
    Given I have an active Share
    When I view the Share on my Shares tab
    Then I cannot edit its filters, sort, or expiry
    And to change anything I must revoke this Share and create a new one

  # ----- Anonymous Friend view -----

  Scenario: Anonymous Friend opens an active Share
    Given an active Share exists for my Movies library with filters and sort applied
    When a Friend opens the Share URL while not logged in
    Then the Friend sees the read-only subset of my library that matches the Share's captured filter
    And the entries are presented in the Share's captured sort order
    And each entry shows its title, cover or poster, release date, and the Owner's full Rating (every Characteristic the Owner recorded)
    And the Friend cannot see my watchlist, my other media types, or library entries outside the shared subset
    And the Friend sees no cross-action buttons and no status badges

  Scenario: Anonymous Friend further sorts and filters within the shared subset
    Given an anonymous Friend is viewing an active Share
    When the Friend changes the sort or applies an additional filter using the same controls available to me on my own library view
    Then the change applies only to the subset already loaded for this Share
    And it does not change which entries the Share itself exposes

  # ----- Logged-in Friend view (cross-actions) -----

  Scenario: Logged-in Friend sees cross-actions and the Uncompleted filter
    Given an active Share for my Movies library
    When a Friend who is a logged-in User opens the Share URL
    Then for each row that is not on the Friend's own Movies watchlist or in their Movies library, the Friend sees two cross-action buttons: "Add to my watchlist" and "Complete and rate now"
    And the Friend additionally sees an "Uncompleted only" filter that, when enabled, hides every row already in the Friend's own Movies library

  Scenario: Cross-action — add to my watchlist from a Share
    Given a logged-in Friend is viewing an active Share that contains "Arrival"
    And "Arrival" is not on the Friend's own Movies watchlist or in their Movies library
    When the Friend clicks "Add to my watchlist" on that row
    Then "Arrival" is added to the Friend's own Movies watchlist
    And the row's affordance flips to a status badge "Already on your watchlist"
    And a toast confirms the addition

  Scenario: Cross-action — Complete and rate from a Share
    Given a logged-in Friend is viewing an active Share that contains "Arrival"
    And "Arrival" is not on the Friend's own Movies watchlist or in their Movies library
    When the Friend clicks "Complete and rate now" and submits a Rating containing at least Overall Enjoyment
    Then "Arrival" appears in the Friend's own Movies library
    And the row's affordance flips to "Already in your library — your Overall Enjoyment: <value>"
    And the Friend sees both their own Rating and the Owner's Rating side by side on this row

  Scenario: Logged-in Friend sees status badges for entries they have already touched
    Given a logged-in Friend is viewing an active Share
    When the Share contains an entry already on the Friend's own watchlist
    Then that row shows a status badge "Already on your watchlist" instead of the cross-action buttons
    And when the Share contains an entry already in the Friend's own library
    Then that row shows a status badge "Already in your library" with the Friend's own Overall Enjoyment alongside the Owner's Rating

  # ----- Owner viewing their own Share -----

  Scenario: Owner opens their own Share URL
    Given I have created an active Share for my Movies library
    When I open the Share URL while logged in as the Owner
    Then I see the same anonymous content (no cross-actions for myself)
    And a banner at the top reads "This is one of your shared views"
    And the banner contains a link to the Shares tab for this media type where I can revoke it

  # ----- Liveness -----

  Scenario: Share is live - newly qualifying entries appear automatically
    Given an active Share with filter "Overall Enjoyment >= 8"
    When I add a new movie to my library and rate its Overall Enjoyment as 9
    Then the new movie appears in the Share the next time the Friend loads it

  Scenario: Share is live - entries that no longer qualify disappear
    Given an active Share with filter "Overall Enjoyment >= 8"
    And the Share currently includes a film I rated 9
    When I lower my Overall Enjoyment for that film to 7
    Then the film no longer appears in the Share the next time the Friend loads it

  # ----- Empty / revoked / expired states -----

  Scenario: Empty subset shows the captured filter pill alongside the empty message
    Given an active Share whose captured filter currently matches no entries in my library
    When a Friend opens the Share URL
    Then the Friend sees a message stating "No entries currently match this share's filters"
    And the Share's captured filter and sort are displayed as a pill so the Friend can see what would qualify
    And the message is distinct from the revocation and expiry messages

  Scenario: Revoking a Share is terminal
    Given I have an active Share
    When I revoke it from my Shares tab (see manage-shares.md for the modal-confirmed flow)
    Then the Share becomes inactive immediately
    And a Friend opening the URL sees the message "This share link is no longer active"
    And there is no way to un-revoke the Share — to share the same picks again I must create a new Share with a new URL

  Scenario: Expired Share shows a distinct message and is terminal
    Given a Share whose expiry has passed
    When a Friend opens the URL
    Then the Friend sees a message indicating the link expired and the date on which it expired
    And the message is distinct from the revocation message
    And there is no way to extend the expiry — to share the same picks again I must create a new Share with a new URL

  Scenario: Revoked and expired Shares stay resolvable for Friends but are hidden from the Owner
    Given I have one Share I revoked yesterday and one Share that expired last month
    When a Friend opens either URL
    Then the Friend sees the appropriate terminal message (revoked or expired) defined above
    And no Share URL ever returns a 404 or generic error
    And on my Shares tab for this media type I see only my active Shares — neither hidden Share appears in my management surface (see manage-shares.md)

  # ----- Per-media-type strictness -----

  Scenario: Shares are per media type
    Given I have a Share for my Movies library
    When the Friend opens that Share
    Then the Friend sees only Movies entries
    And no Books, TV, or Games appear in the shared subset
```
