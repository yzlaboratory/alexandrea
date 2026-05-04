# Browse and search a media catalog

The logged-in user browses one media type's catalog at a time, sourced from an
external provider (TMDB for Movies and TV, OpenLibrary for Books, IGDB for
Games). The same browse mechanics apply to all four types, parameterised by
`media_type`. From this page the user can either add an entry to their
watchlist (see `add-to-watchlist.md`) or complete it directly (see
`complete-entry.md`). The catalog is **live** — title, cover, release date,
genres, and external rating are fetched from the provider through a
rate-limit-only cache (see ADR 0001) and never stored locally. Lists use
**infinite scroll**: 20 entries are fetched at a time and appended as the
user scrolls.

```gherkin
Feature: Browse and search a media catalog
  As a logged-in user
  I want to browse, search, sort, and filter the catalog of one media type at a time
  So that I can discover entries to add to my watchlist or log directly to my library

  Background:
    Given I am logged in
    And I have selected one of "Movies", "TV", "Books", or "Games" from the navigation

  Scenario Outline: Initial browse view shows what is currently popular for the provider
    When I open the catalog browse page for <media_type>
    Then I see entries from <provider>'s nearest "popular" feed: <provider_endpoint>
    And each entry shows its title, cover or poster, release date, and external rating
    And the external rating is shown raw with the provider's native scale (e.g. "7.8/10 TMDB", "4.2/5 OpenLibrary", "84/100 IGDB")
    And the list is rendered as an infinite-scrolling feed in chunks of 20 entries

    Examples:
      | media_type | provider     | provider_endpoint                                |
      | Movies     | TMDB         | /movie/popular                                   |
      | TV         | TMDB         | /tv/popular                                      |
      | Books      | OpenLibrary  | /trending/daily.json                             |
      | Games      | IGDB         | games sorted by total_rating_count desc          |

  Scenario: Searching by title
    Given I am on the Movies catalog browse page
    When I search for "blade runner"
    Then I see catalog entries whose title matches "blade runner"
    And the popular list is replaced by the search results
    And the results render as an infinite-scrolling feed in chunks of 20

  Scenario Outline: Sorting catalog results
    Given I have a list of catalog results on screen
    When I change the sort to <sort> in <direction> direction
    Then the list reorders accordingly
    And my current search and filters are preserved
    And my sort choice is persisted per (user, surface, media_type)

    Examples:
      | sort            | direction (default) |
      | popularity      | desc                |
      | release date    | desc                |
      | title           | asc                 |
      | external rating | desc                |

  Scenario: Filtering by genre
    Given I have a list of catalog results on screen
    When I apply a genre filter
    Then only entries matching that genre remain in the list

  Scenario: Filtering by original language
    Given I have a list of catalog results on screen
    When I apply an "original language = English" filter
    Then only entries whose upstream `original_language` (or equivalent per provider) is English remain in the list

  Scenario: Filtering by available-in language
    Given I have a list of catalog results on screen
    When I apply an "available in = German" filter
    Then only entries that the provider reports as available in German (TMDB spoken/translated languages, OpenLibrary translation editions, IGDB language_supports) remain in the list
    And entries whose original language is non-German but that have a German release qualify

  Scenario Outline: Type-appropriate length filters
    Given I am browsing the <media_type> catalog
    When I apply the filter <filter>
    Then only entries matching that filter remain in the list

    Examples:
      | media_type | filter     |
      | Movies     | runtime    |
      | TV         | runtime    |
      | Books      | page count |

  Scenario: Combining multiple filters narrows the list further
    Given I am browsing the Movies catalog
    When I apply both a genre filter and an original-language filter
    Then only entries matching all applied filters remain

  Scenario: Search returns no matches
    Given I am on the Books catalog browse page
    When I search for a string that matches no titles
    Then I see an empty-results message offering to clear the search

  Scenario: External catalog is unreachable
    Given the external catalog for the selected media type is currently unreachable
    When I open the catalog browse page
    Then I see an error stating the catalog is temporarily unavailable
    And I am offered a way to retry
    And no local data is corrupted by the failure
```
