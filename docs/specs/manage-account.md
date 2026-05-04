# Manage my account

The logged-in **User** changes their email, changes their password,
or deletes their account from the **Account settings** page reached
from the account menu. Account-level — not media-type-scoped.

- The **email change** flow re-verifies on the new address using the
  same policy as `create-account.md`. The old address keeps login
  until the new one is verified, and the old address gets a
  notification with a 7-day revert window.
- The **password change** flow follows the NIST 800-63B policy of
  ADR 0002 and **invalidates all other active sessions** — the
  current device stays signed in.
- The **delete account** flow is modal-confirmed, password-gated,
  and irreversible. It cascade-deletes every per-user row and
  renders all the Owner's Shares terminally inactive for any Friend
  who has the URL.

```gherkin
Feature: Manage my account
  As a logged-in User
  I want to change my email, change my password, or delete my account
  So that I can keep my login current, rotate credentials, and leave the service cleanly

  Background:
    Given I am logged in
    And I have opened the Account settings page from the account menu

  # ----- Change email -----

  Scenario: Initiate an email change
    When I submit a new valid email address along with my correct current password
    Then the new address enters an unverified state
    And a verification email is sent to the new address valid for 24 hours
    And a notification email is sent to my current address explaining that the change has been requested and offering a "revert this change" link valid for 7 days
    And I can continue to log in with my current address until the new one is verified

  Scenario: Verify the new email address within the 24-hour window
    Given I have requested an email change and the verification email is less than 24 hours old
    When I click the verification link in the new address
    Then my account email becomes the new address
    And my old address is deactivated for login
    And I see a confirmation that the change is complete

  Scenario: Email change verification link is opened after the 24-hour window
    Given a verification link was sent to the new address more than 24 hours ago
    When I click the expired link
    Then I see a message that the link has expired
    And my account email remains the original address
    And I am offered a way to request the change again

  Scenario: Wrong current password blocks the email change
    When I submit a new email address with the wrong current password
    Then I see a generic "credentials are invalid" error
    And no verification email is sent to the new address
    And no notification email is sent to my current address

  Scenario: New email is already in use
    Given an account already exists for "ada@example.com"
    When I submit "ada@example.com" with my correct current password as my new address
    Then I see an error stating the address is already registered
    And no verification email is sent
    And no notification email is sent to my current address

  Scenario: New email is malformed
    When I submit a string that is not a valid email address with my correct current password
    Then I see an error stating the email is invalid
    And no verification email is sent
    And no notification email is sent to my current address

  Scenario: Revert an in-flight email change from the old address within 7 days
    Given I have requested an email change but the new address has not yet been verified
    And the revert link in the notification email is less than 7 days old
    When I click the revert link
    Then the in-flight email change is cancelled
    And my account email remains the original address
    And the verification token for the new address is invalidated

  # ----- Change password while logged in -----

  Scenario: Successful password change kicks all other sessions
    Given I have at least one other active session for this account on a different device
    When I submit my correct current password and a new password that is at least 12 characters and not in the HaveIBeenPwned breach corpus
    Then my password is updated
    And I remain logged in on the current device
    And every other active session for this account is invalidated immediately
    And I see a confirmation that the change is complete

  Scenario: Wrong current password blocks the password change
    When I submit the wrong current password with any new password
    Then I see a generic "credentials are invalid" error
    And my password is not updated
    And no other sessions are invalidated

  Scenario: New password fails the NIST 800-63B policy
    When I submit my correct current password and a new password that is shorter than 12 characters or appears in the HaveIBeenPwned breach corpus
    Then I see an error explaining which rule was not met (per ADR 0002)
    And my password is not updated
    And no other sessions are invalidated

  # ----- Delete account -----

  Scenario: Delete account opens a modal that requires the current password to confirm
    When I click "Delete my account"
    Then a modal asks me to confirm, explaining that deletion is irreversible and listing what will be cascade-deleted: my watchlists, libraries, ratings, completion dates, and all my Shares (which will return a "no longer active" message to any Friend who has the URL)
    And the modal requires me to submit my current password to confirm

  Scenario: Cancelling the delete-account modal leaves the account untouched
    Given the delete-account modal is open
    When I cancel the modal
    Then nothing is deleted
    And I remain logged in on the Account settings page

  Scenario: Confirming deletion with the correct password destroys all per-user data
    Given the delete-account modal is open
    When I submit my correct current password and confirm
    Then my account is deleted
    And all my watchlists, libraries, ratings, and completion dates for every media type are deleted
    And every Share I ever created — active, revoked, or expired — returns the "no longer active" terminal message to any Friend who opens the URL from now on
    And every active session for this account on every device is invalidated immediately
    And I am redirected to a page confirming the deletion
    And I am no longer logged in

  Scenario: Confirming deletion with the wrong password aborts the delete
    Given the delete-account modal is open
    When I submit the wrong current password and confirm
    Then I see a generic "credentials are invalid" error
    And nothing is deleted
    And I remain logged in on the Account settings page

  Scenario: A deleted account's email is immediately available for fresh signup
    Given an account for "ada@example.com" was just deleted
    When a new visitor signs up via create-account.md using "ada@example.com"
    Then signup proceeds normally per create-account.md
    And the new account has a fresh User id with no carried-over watchlists, libraries, ratings, completion dates, or Shares
```
