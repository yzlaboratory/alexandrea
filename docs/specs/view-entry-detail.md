# View an entry's detail

Pressing any entry in any of the four entry-grid surfaces — Catalog,
Watchlist, Library, or a Share view — opens the entry's **detail
overlay**: a dismissable modal that renders the upstream provider's
full metadata plus, when relevant, the opener's own state (Watchlist
pill, Library Rating + Completion Dates) or the Owner's Rating when
reached from a Share view. The overlay is **deep-linkable** — opening
it changes the URL, the browser back button closes it, and refreshing
while it is open re-opens it on the same surface. The grid behind the
overlay never loses its scroll position on dismiss. The URL shape
is a path segment containing the upstream external id under the
surface route — e.g. `/movies/catalog/27205`, `/books/library/OL45804W`,
`/share/<token>/1942` — defined in ADR 0008.

Upstream metadata is fetched live through the rate-limit-only cache
(ADR 0001). A Catalog Item the upstream has removed renders a
**"removed by &lt;provider&gt;"** affordance per ADR 0009 — the local
row, Rating, and Completion Dates are preserved.

The overlay's a11y obligations — `role="dialog"`, focus trap on open,
focus restoration to the originating grid item on dismiss, keyboard
equivalents for every gesture (including the mobile drag-down
dismiss) — are pinned in ADR 0010 and not repeated as scenarios here.

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

  Scenario: Detail overlay shape on desktop is a centered modal
    Given I am on a wide (desktop) viewport
    When the detail overlay opens
    Then the overlay is rendered as a centered modal card over a dimmed backdrop that covers the grid
    And the grid behind the backdrop retains its scroll position

  Scenario: Detail overlay shape on mobile is a bottom sheet
    Given I am on a narrow (mobile) viewport
    When the detail overlay opens
    Then the overlay is rendered as a bottom sheet that slides up from the bottom of the viewport
    And the grid remains partially visible above the sheet, retaining its scroll position

  Scenario Outline: Dismissing the overlay returns me to the grid at the same scroll position
    Given the detail overlay is open over the grid
    When I dismiss the overlay by <gesture>
    Then the overlay closes
    And the grid is shown again at the exact scroll position I was at before opening the overlay
    And the URL updates back to the surface URL without the entry deep link

    Examples:
      | gesture                                                                |
      | clicking or tapping outside the overlay (the backdrop or visible grid) |
      | pressing the close affordance inside the overlay                       |
      | pressing the Escape key (desktop only)                                 |
      | pressing the browser back button                                       |
      | dragging the bottom sheet down past the dismissal threshold (mobile)   |

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

  Scenario: A Catalog Item the upstream has removed renders a "removed by <provider>" affordance
    Given the entry I press has been confirmed removed by its upstream provider on a fresh fetch (per ADR 0009)
    When the detail overlay opens
    Then the overlay shows a "Removed by <provider>" affordance in place of upstream metadata, where <provider> is TMDB for Movies and TV, OpenLibrary for Books, or IGDB for Games
    And my local Rating and Completion Dates for the entry remain visible (when the entry is in my Library)
    And the only affordance offered is "Remove from my library" (or "Remove from my watchlist" when the entry is on my watchlist)
    And no add or complete affordances are shown
    And dismissing the overlay returns me to the grid normally

  Scenario: An upstream "currently unavailable" failure does not flip the entry to "removed"
    Given the upstream provider for this media type returns a transient failure (per ADR 0009)
    When the detail overlay opens
    Then the overlay shows the existing "currently unavailable" transient message — not the "removed by <provider>" affordance
    And the local row, Rating, and Completion Dates are unaffected
    And the next successful fresh fetch resolves the transient state without further user action
```

## Open questions

- The exact layout of the rating form inside the overlay (modal-in-modal
  vs. inline expansion) is intentionally not specified here — that is a
  visual-design decision that does not change the behaviour described in
  `complete-entry.md`.
