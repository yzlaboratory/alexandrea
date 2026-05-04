# Add an entry to my watchlist

From a catalog browse page, the logged-in user adds a Catalog Item to the
**Watchlist** for the currently-selected media type. Watchlists are strictly
per media type (see ADR 0004) — adding a movie has no effect on the books
watchlist. Feedback for every add/remove/complete action is **inline state
change + a toast**; destructive actions get an undo affordance in the toast.

```gherkin
Feature: Add an entry to my watchlist
  As a logged-in user
  I want to add catalog entries to the watchlist for the same media type
  So that I can keep track of what I plan to watch, read, or play

  Background:
    Given I am logged in
    And I have selected a media type
    And I am viewing a catalog entry for that media type

  Scenario: Add an unsaved entry to the watchlist
    Given the entry is not on my watchlist or in my library for this media type
    When I add it to my watchlist
    Then it appears on my watchlist for this media type
    And the action affordance on the catalog entry flips inline to indicate the entry is already on the watchlist
    And a brief toast confirms "Added to your <media_type> watchlist"

  Scenario: Adding the same entry twice has no effect
    Given the entry is already on my watchlist for this media type
    When I attempt to add it to my watchlist again
    Then no duplicate is created
    And my watchlist count for this media type does not change
    And the affordance continues to indicate the entry is already on the watchlist

  Scenario: Adding to the watchlist is per media type
    Given a movie titled "Arrival" is on my Movies watchlist
    When I view any entry in the Books catalog
    Then no Books entry is affected
    And my Books watchlist is unchanged

  Scenario: Attempting to add an entry already in my library
    Given the entry is already in my library for this media type
    When I attempt to add it to my watchlist
    Then I see a toast notice that the entry is already in my library
    And the entry is not added to my watchlist
    And the toast offers a link to the entry in my library
```
