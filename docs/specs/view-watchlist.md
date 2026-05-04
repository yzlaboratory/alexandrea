# View my watchlist

The logged-in user views the **Watchlist** for one media type. Only entries
that have **not** been Completed appear here — once Completed, an entry moves
to the **Library** (see `complete-entry.md` and `browse-library.md`). The
list uses infinite scroll in chunks of 20, like the catalog. Removal from
the Watchlist is non-destructive (no Rating exists yet) and is confirmed by
a toast with undo, not a modal.

```gherkin
Feature: View my watchlist
  As a logged-in user
  I want to view, search, sort, and filter my watchlist for one media type
  So that I can pick what to consume next

  Background:
    Given I am logged in
    And I have selected a media type
    And I open my watchlist for this media type

  Scenario: Watchlist shows only entries I have not yet Completed
    Given I have three Movies on my watchlist and one Movie I have already Completed
    When I open my Movies watchlist
    Then I see only the three not-yet-Completed movies
    And the movie I Completed does not appear here
    And the list is rendered as an infinite-scrolling feed in chunks of 20 entries

  Scenario: Empty watchlist
    Given I have no entries on my watchlist for this media type
    When I open the watchlist
    Then I see an empty-state message inviting me to browse the catalog

  Scenario: Searching the watchlist by title
    Given my Movies watchlist contains entries with various titles
    When I search for a substring of one title
    Then only matching entries remain in view

  Scenario Outline: Sorting the watchlist
    When I change the sort to <sort> in <direction> direction
    Then my watchlist reorders accordingly
    And my current search and filters are preserved
    And my sort choice is persisted per (user, surface, media_type)

    Examples:
      | sort            | direction (default) |
      | date added      | desc                |
      | title           | asc                 |
      | release date    | desc                |
      | external rating | desc                |

  Scenario: Filtering the watchlist by genre
    When I apply a genre filter
    Then only watchlist entries matching that genre remain

  Scenario: Removing an entry from the watchlist
    Given an entry is on my watchlist
    When I remove it from the watchlist
    Then it disappears from the watchlist immediately
    And it is not added to my library
    And it remains discoverable in the catalog browse
    And a toast confirms the removal and offers an "Undo" affordance for 5 seconds

  Scenario: Undo removal within the toast window
    Given I have just removed an entry from my watchlist and the undo toast is visible
    When I click "Undo" before the toast disappears
    Then the entry is restored to my watchlist in its previous position

  Scenario: Watchlists are per media type
    Given my Movies watchlist contains "Dune"
    When I switch to my Books watchlist
    Then I do not see "Dune" or any other movie in my Books watchlist
```
