# View an entry's detail

Pressing any entry in any of the four entry-grid surfaces — Catalog,
Watchlist, Library, or a Share view — opens the entry's **detail
overlay**: a dismissable modal that renders the upstream provider's
full metadata plus, when relevant, the opener's own state (Watchlist
pill, Library Rating + Completion Dates) or the Owner's Rating when
reached from a Share view. The overlay is **deep-linkable** — opening
it changes the URL, the browser back button closes it, and refreshing
while it is open re-opens it on the same surface. The grid behind the
overlay never loses its scroll position on dismiss.

Upstream metadata is fetched live through the rate-limit-only cache
(ADR 0001). A Catalog Item that has entered `pending_removal` per
ADR 0003 renders a "currently unavailable" state in the overlay
rather than a partial detail page.

```gherkin
Feature: View an entry's detail in an overlay
  As any opener of an entry-grid surface
  I want a detail overlay that shows full metadata and my own state for the entry
  So that I can decide what to do with it without losing my place in the grid

  Background:
    Given I am on a surface that renders entries as a grid (Catalog, Watchlist, Library, or a Share view)

  Scenario: Pressing a grid item opens the detail overlay
    When I press an entry in the grid
    Then a detail overlay opens above the grid
    And the URL updates to a deep link for that entry on the current surface
    And the grid behind the overlay is visually de-emphasised but retains its scroll position

  Scenario Outline: Dismissing the overlay returns me to the grid at the same scroll position
    Given the detail overlay is open over the grid
    When I dismiss the overlay by <gesture>
    Then the overlay closes
    And the grid is shown again at the exact scroll position I was at before opening the overlay
    And the URL updates back to the surface URL without the entry deep link

    Examples:
      | gesture                                         |
      | clicking outside the overlay                    |
      | pressing the close affordance inside the overlay|
      | pressing the Escape key                         |
      | pressing the browser back button                |

  Scenario: Refreshing while the overlay is open re-opens it
    Given the detail overlay is open over the grid for a specific entry
    When I refresh the page
    Then the same surface loads with the same overlay open for the same entry
    And the grid is rendered behind the overlay paged as needed to include the entry's row when known

  Scenario Outline: Movies and TV detail field set
    Given the entry I press is a <media_type> entry
    When the detail overlay opens
    Then the overlay shows synopsis, runtime, release date, original language, available languages, genres, top-billed cast, director or creator, cover or poster, and the upstream provider's external rating

    Examples:
      | media_type |
      | Movies     |
      | TV         |

  Scenario: Books detail field set
    Given the entry I press is a Books entry
    When the detail overlay opens
    Then the overlay shows synopsis, page count, publication date, authors, original language, the set of distinct languages drawn from the work's editions, subjects or genres, cover, and the upstream provider's external rating when one is present (see ADR 0006)

  Scenario: Games detail field set
    Given the entry I press is a Games entry
    When the detail overlay opens
    Then the overlay shows synopsis, release date, platforms, genres, supported languages, developer, publisher, cover, and the upstream provider's external rating

  Scenario: Detail overlay opened from Catalog offers add and complete affordances
    Given I am on the Catalog grid for a media type
    And the entry I press is not on my watchlist or in my library for this media type
    When the detail overlay opens
    Then the overlay offers "Add to watchlist" and the media-type-appropriate "Mark as watched / read / played" affordance per add-to-watchlist.md and complete-entry.md
    And after I take an action the overlay reflects the new state inline (a status badge or pill) without closing

  Scenario: Detail overlay opened from Catalog for an entry already on my watchlist or in my library shows the matching status
    Given I am on the Catalog grid
    And the entry I press is already on my watchlist or in my library for this media type
    When the detail overlay opens
    Then the overlay shows the matching status pill ("On your watchlist" or "In your library")
    And the overlay does not offer "Add to watchlist" again

  Scenario: Detail overlay opened from Watchlist offers complete and remove affordances
    Given I am on the Watchlist grid for a media type
    When the detail overlay opens for one of my watchlist entries
    Then the overlay shows an "On your watchlist" pill
    And the overlay offers the media-type-appropriate "Mark as watched / read / played" affordance and a "Remove from watchlist" affordance per complete-entry.md and view-watchlist.md

  Scenario: Detail overlay opened from Library shows my Rating, Completion Dates, and edit/delete/recomplete affordances
    Given I am on the Library grid for a media type
    When the detail overlay opens for one of my library entries
    Then the overlay shows my current Rating including every Characteristic I recorded
    And the overlay shows every Completion Date for the entry
    And the overlay offers "Edit Rating", a media-type-appropriate "Mark as watched / read / played again", and "Delete from library" affordances per complete-entry.md
    And the upstream metadata is shown alongside my own state

  Scenario: Detail overlay opened from a Share view shows the Owner's Rating
    Given I am viewing a Share for a media type
    When the detail overlay opens for an entry in the shared subset
    Then the overlay shows the Owner's full Rating for the entry alongside the upstream metadata
    And when I am not logged in the overlay shows none of my own state and offers no actions
    And when I am logged in and the entry is on my own watchlist or in my own library, the overlay shows the matching status badge and my own Rating side by side with the Owner's per share-top-rated.md
    And when I am logged in and the entry is not yet on my own watchlist or in my own library, the overlay offers the cross-actions "Add to my watchlist" and "Complete and rate now" defined in share-top-rated.md

  Scenario: A Catalog Item in pending_removal renders unavailable
    Given the entry I press has been marked pending_removal per ADR 0003
    When the detail overlay opens
    Then the overlay shows a "currently unavailable" message in place of metadata
    And no add, complete, rating, or remove affordances are shown
    And dismissing the overlay returns me to the grid normally
```

## Open questions

- The exact layout of the rating form inside the overlay (modal-in-modal
  vs. inline expansion) is intentionally not specified here — that is a
  visual-design decision that does not change the behaviour described in
  `complete-entry.md`.
