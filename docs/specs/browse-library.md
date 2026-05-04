# Browse my library

The logged-in user views the **Library** for one media type — every entry
they have **Completed**, with the user's Rating and Completion Dates
attached. Entries arrive here via `complete-entry.md` (from the watchlist
or directly from the catalog) and may only leave via permanent deletion in
that spec — there is no path back to the watchlist. The list uses infinite
scroll in chunks of 20.

Filtering by a per-aspect Characteristic **excludes** entries that have no
value for that Characteristic (e.g. `Story ≥ 8` excludes Movies that were
Completed without a Story score, and excludes all Books and Games — neither
media type's v1 form offers Story). Filtering or sorting by **Overall
Enjoyment** never excludes anything because every Rating has it.

```gherkin
Feature: Browse my library
  As a logged-in user
  I want to view, search, sort, and filter my library for one media type
  So that I can revisit my history, compare ratings, and decide what to share

  Background:
    Given I am logged in
    And I have selected a media type
    And I open my library for this media type

  Scenario: Empty library
    Given I have not Completed anything for this media type
    When I open the library
    Then I see an empty-state message inviting me to browse the catalog or my watchlist

  Scenario: Library renders as an infinite-scrolling feed
    Given I have many entries in my library for this media type
    When I open the library
    Then I see entries fetched in chunks of 20
    And more chunks load as I scroll

  Scenario: Searching the library by title
    When I search for a substring of a title in my library
    Then only matching library entries remain in view

  Scenario Outline: Sorting the library
    When I change the sort to <sort> in <direction> direction
    Then library entries reorder accordingly
    And my current search and filters are preserved
    And my sort choice is persisted per (user, surface, media_type)

    Examples:
      | sort            | direction (default) |
      | overall rating  | desc                |
      | completion date | desc                |
      | title           | asc                 |
      | release date    | desc                |

  Scenario: Filter the library by genre
    When I apply a genre filter
    Then only library entries matching that genre remain

  Scenario: Filter the library by completion year
    When I apply a completion-year filter
    Then only library entries with at least one Completion Date in that year remain

  Scenario: Filter the library by Overall Enjoyment range
    When I apply an "Overall Enjoyment between 7 and 9" filter
    Then only library entries whose Overall Enjoyment falls within the range remain
    And no entry is excluded for missing Overall Enjoyment, because every Rating contains it

  Scenario: Filter by minimum value on an optional per-aspect Characteristic
    Given my Movies library contains entries that were Completed with various Characteristic combinations
    When I apply a filter "Story >= 8"
    Then only entries whose Rating contains a Story score of 8 or higher remain
    And entries whose Rating does not contain a Story score (because the user left it blank, or because the entry's media type does not offer Story) are excluded

  Scenario: Books and Games are unaffected by per-aspect filters they do not offer
    Given my Books library contains entries
    And I switch to my Movies library and apply "Story >= 8"
    When I switch back to my Books library
    Then the per-aspect filter does not transfer between media types
    And the Books library shows all my Books entries normally

  Scenario: Combine minimum-value filters on multiple per-aspect Characteristics
    Given my Movies library contains entries with various Characteristic combinations
    When I apply both "Story >= 8" and "Visuals >= 7"
    Then only entries whose Rating contains both a Story value of at least 8 and a Visuals value of at least 7 remain

  Scenario: Combine an Overall Enjoyment filter with per-aspect filters
    Given my Movies library contains entries with various Ratings
    When I apply "Overall Enjoyment >= 7" together with "Music >= 8"
    Then only entries that satisfy both filters remain

  Scenario: Library shows multiple Completion Dates if present
    Given an entry in my library has been Completed on more than one date
    When I view the entry in the library
    Then all of its Completion Dates are visible
    And only its current single Rating is shown

  Scenario: Libraries are per media type
    Given my Movies library contains entries
    When I switch to my Books library
    Then I do not see any movies in my Books library
```

## Open questions

- Sorting by an **individual per-aspect Characteristic** is intentionally
  not in scope (only filtering by per-aspect minimums is) — revisit if
  needed.
