# Complete an entry and rate it

The logged-in user **Completes** a watchlist or catalog entry — the canonical
verb for finishing a piece of media, applied uniformly across all four media
types. The UI varies the label by media type ("Mark as watched" for
Movies/TV, "Mark as read" for Books, "Mark as played" for Games), but the
domain action and stored Completion Date are the same. Completed entries
live in the **Library** for that media type. Re-Completing is supported as
multiple Completion Dates with a **single overwriting Rating**.

The **Rating** is a JSON document of one-or-more **Characteristics**, each
scored 1–10. **Overall Enjoyment is the only required Characteristic** on
every Rating, regardless of media type; all other Characteristics are
optional. The Rating shape per media type in v1 is:

- **Movies / TV** → Story, Acting, Music, Visuals, Direction, Overall
  Enjoyment (Overall required, the rest optional)
- **Books / Games** → Overall Enjoyment only (per-aspect Characteristics
  deferred — see `OOS.md` item 8)

Genre is **irrelevant** to Rating shape; the previous "Documentary" special
case is removed. The lifecycle is **one-way**: a Library entry cannot be
moved back to the Watchlist. Removing it from the Library is a permanent
delete that discards all Completion Dates and the Rating; rediscovery is
through the catalog browse. Throughout this spec, "today's date" means
today in the **user's browser timezone** at submission time, stored as a
`YYYY-MM-DD` calendar date with no time-of-day component (see
`CONTEXT.md` under Completion Date).

```gherkin
Feature: Complete an entry and rate it
  As a logged-in user
  I want to Complete a catalog or watchlist entry and record my Rating
  So that the entry moves into my library with my personal scores attached

  Background:
    Given I am logged in
    And I have selected a media type

  Scenario Outline: Complete an entry from the watchlist with the per-media-type Rating form
    Given an entry is on my <media_type> watchlist
    When I Complete the entry
    And I submit a Rating that contains at least an Overall Enjoyment score on the 1-10 scale
    Then the entry appears in my <media_type> library
    And today's date is recorded as a Completion Date for the entry
    And the entry no longer appears on my <media_type> watchlist
    And a toast confirms the Completion

    Examples:
      | media_type |
      | Movies     |
      | TV         |
      | Books      |
      | Games      |

  Scenario: Movies/TV optional Characteristics are stored only when filled
    Given a Movies entry is on my watchlist
    When I Complete it and submit Story=8, Visuals=9, Overall Enjoyment=9, leaving Acting, Music, and Direction blank
    Then the stored Rating contains exactly Story=8, Visuals=9, and Overall Enjoyment=9
    And no implicit value is filled in for Acting, Music, or Direction

  Scenario: Submitting a Rating without Overall Enjoyment is rejected
    Given I am Completing any entry from any media type
    When I attempt to submit a Rating that has no Overall Enjoyment value
    Then the entry is not added to my library
    And the entry remains on my watchlist (or remains uncompleted if I started from the catalog)
    And I see an error indicating that Overall Enjoyment is required

  Scenario: Complete an entry directly from the catalog
    Given an entry is in the catalog and is not on my watchlist or in my library
    When I Complete it directly from the catalog
    And I submit a Rating containing at least Overall Enjoyment
    Then the entry appears in my library
    And today's date is recorded as a Completion Date
    And the entry never appeared on my watchlist

  Scenario: Re-Completing an entry already in the library opens a blank form
    Given an entry is in my library with at least one Completion Date and a current Rating
    When I Complete it again
    Then the Rating form opens blank — none of my previous Characteristic values is pre-filled
    And after I submit a new Rating containing at least Overall Enjoyment, today's date is appended as a new Completion Date
    And my Rating for the entry is replaced wholesale by the newly submitted Rating
    And no duplicate library entry is created

  Scenario: Same-day re-completion appends a duplicate Completion Date
    Given an entry in my library already has a Completion Date of today
    When I Complete it again today and submit a new Rating containing at least Overall Enjoyment
    Then today's date is appended again as an additional Completion Date — the list now contains today's date twice
    And my Rating is replaced wholesale by the newly submitted Rating
    And no duplicate library entry is created

  Scenario: Concurrent edits to the same Rating resolve as last-write-wins
    Given I have the Rating editor open for the same library entry in two browser tabs
    And I submit a Rating from tab A first
    When I subsequently submit a different Rating from tab B
    Then my stored Rating becomes whatever tab B submitted, in full — no merge with tab A's values
    And no version-conflict warning is shown to either tab
    And the entry's recorded Completion Dates are unchanged by the edits (the editor never appends a Completion Date — only the Complete action does, per the previous scenarios)

  Scenario: Edit a Rating after the fact pre-fills the form with current values
    Given an entry is in my library with a Rating that contains Story=8 and Overall Enjoyment=9
    When I open the Rating editor
    Then the form is pre-filled with Story=8 and Overall Enjoyment=9
    And I may change any value, clear any optional Characteristic, or add a value to any optional Characteristic that is currently empty
    And Overall Enjoyment cannot be cleared
    And on submit the new Rating replaces the old one wholesale
    And the entry's recorded Completion Dates are unchanged

  Scenario: Delete an entry from the library is permanent
    Given an entry is in my library with one or more Completion Dates and a Rating
    When I delete it from the library
    Then the entry no longer appears in my library
    And all Completion Dates and the Rating are discarded
    And the entry is not restored to my watchlist
    And the entry remains discoverable in the catalog browse
    And a toast confirms the deletion and offers an "Undo" affordance for 5 seconds

  Scenario: Undo a library deletion within the toast window
    Given I have just deleted a library entry and the undo toast is visible
    When I click "Undo" before the toast disappears
    Then the entry, all its Completion Dates, and its Rating are restored

  Scenario: A Catalog Item removed upstream cascade-deletes my library entry
    Given an entry in my library references an external_id that the upstream provider has removed
    And the removal has been confirmed twice per ADR 0003
    When the cascade fires
    Then the entry, its Completion Dates, and its Rating are dropped from my library
    And no notification is shown to me (the entry simply ceases to appear)
```
