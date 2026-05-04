# View and manage my active Shares

The library **Owner** sees and manages the **active** Shares they have
created for one media type's Library, on the per-media-type **Shares**
surface tab. Revoked and expired Shares stay resolvable for any Friend
who has the URL (terminal messages defined in `share-top-rated.md`),
but they are hidden from the Owner's management view — there is no
share-history surface in v1. See `share-top-rated.md` for the Share
creation flow and Friend-facing behaviour.

```gherkin
Feature: View and manage my active Shares
  As an Owner
  I want to see and manage the active Shares I have created for one media type's Library
  So that I can copy a link to send, revoke a link I no longer want active, and notice links nearing expiry

  Background:
    Given I am logged in
    And I have selected a media type
    And I am on the Shares tab for this media type

  Scenario: Empty state when I have no active Shares
    Given I have never created a Share for this media type, or every Share I created is revoked or expired
    When I open the Shares tab
    Then I see an empty-state message inviting me to create a Share from my Library
    And the message links to my Library for this media type

  Scenario: Active Shares are listed, most recently created first
    Given I have created two active Shares for my Movies Library
    When I open the Shares tab for Movies
    Then I see one row per active Share, sorted by created-at descending
    And each row shows the Share's captured filter+sort as a pill, its expiry date or "Never", and the date I created it
    And each row offers a "Copy URL" affordance and a "Revoke" affordance
    And the Share URL itself is never displayed on the page

  Scenario: Revoked and expired Shares do not appear here
    Given I have one active Share, one Share I revoked yesterday, and one Share that expired last month
    When I open the Shares tab
    Then I see only the active Share
    And the revoked and expired Shares are not shown
    And both hidden Shares remain resolvable for any Friend who has the URL (see share-top-rated.md)

  Scenario: Copy a Share URL
    Given I am on the Shares tab and a row shows an active Share
    When I click "Copy URL"
    Then the Share URL is copied to my clipboard
    And a toast confirms "Link copied"

  Scenario: Revoke an active Share is modal-confirmed and terminal
    Given I am on the Shares tab and a row shows an active Share
    When I click "Revoke" on that row
    Then a modal asks me to confirm, explaining that revocation is terminal and that Friends with the URL will see a "no longer active" message from now on

  Scenario: Cancelling the revoke modal leaves the Share active
    Given the revoke modal is open for one of my active Shares
    When I cancel the modal
    Then the Share remains active
    And the Shares tab is unchanged

  Scenario: Confirming the revoke modal hides the Share from the tab
    Given the revoke modal is open for one of my active Shares
    When I confirm the revocation
    Then the Share's status becomes revoked
    And the row disappears from the Shares tab
    And opening the URL afterwards shows the "no longer active" terminal message defined in share-top-rated.md

  Scenario Outline: Near-expiry Shares show a relative-time tail
    Given an active Share for my Movies Library has expiry <expiry>
    When I open the Shares tab for Movies
    Then the row shows the expiry as <displayed>

    Examples:
      | expiry              | displayed                                |
      | within 7 days       | absolute date plus a tail like "(in 2 days)" |
      | more than 7 days    | absolute date only, no relative tail     |
      | none (never expires)| "Never", with no relative tail           |

  Scenario: Shares tab is per media type
    Given I have one active Share for my Movies Library and one active Share for my Books Library
    When I open the Shares tab for Movies
    Then I see only the Movies Share
    And the Books Share does not appear here
    And opening the Shares tab for Books shows only the Books Share
```
