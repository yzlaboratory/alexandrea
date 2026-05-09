# Manage my account

The logged-in **User** changes their email, changes their password,
or deletes their account from the **Account settings** page reached
from the account menu. Account-level — not media-type-scoped.

- The **email change** flow re-verifies on the new address using the
  same policy as `create-account.md`. The old address keeps login
  until the new one is verified. At initiation, a **notification
  email** goes to the original address — *"an email change to `<new>`
  was requested on your account at `<time>`; if this wasn't you, log
  in and change your password now"* — purely as inbox evidence
  against a hijacked account silently rotating its credential away.
  Once the new address verifies, the change is permanent — there is
  no revert window. The original-address holder's recourse, while
  the new address has not yet verified, is to log in (the old
  address is still the credential) and change the password; that
  kicks any attacker session per ADR 0012 and additionally
  invalidates the pending email-change verification token (also per
  ADR 0012).
- A fresh email-change initiation **supersedes** any previously-
  pending email-change for the account: the prior verification
  token is invalidated. At most one pending email change per
  account at any time.
- The **password change** flow follows the NIST 800-63B policy of
  ADR 0002 and **invalidates all other active sessions** — the
  current device stays signed in (per the matrix in ADR 0012). It
  additionally invalidates any pending email-change verification
  token for the account, per ADR 0012.
- All other auth events in this spec — email-change verification,
  account deletion — invalidate **every** active session for the
  account, per ADR 0012.
- The **delete account** flow is modal-confirmed, password-gated,
  and irreversible. It cascade-deletes every per-user row and
  renders all the Owner's Shares terminally inactive for any Friend
  who has the URL.

All emails sent by these flows are subject to the cross-cutting
email policy in ADR 0011: single `noreply@<domain>` sender, unified
per-recipient rate limit shared across every flow, with the
deletion-confirmation email as the one exempt case.

HIBP fail-open behaviour for the password-change scenarios is per
ADR 0002: if the HaveIBeenPwned breach-check API is unreachable, a
new password that meets the local 12-character rule is accepted
without breach screening, silently. The Gherkin below describes the
intended steady-state behaviour.

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
    And a notification email is sent to my current address explaining that an email change has been requested and instructing me to log in and change my password now if I did not initiate it
    And I can continue to log in with my current address until the new one is verified

  Scenario: Initiating a fresh email change supersedes any pending one
    Given I previously requested an email change to "alice2@example.com" and the verification token is still live
    When I submit a different new valid email address "alice3@example.com" with my correct current password
    Then the verification token for "alice2@example.com" is invalidated
    And a new verification token is issued for "alice3@example.com"
    And clicking the old "alice2@example.com" link no longer changes anything
    And only the "alice3@example.com" change is now pending

  Scenario: Verify the new email address within the 24-hour window
    Given I have requested an email change and the verification email is less than 24 hours old
    When I click the verification link in the new address
    Then my account email becomes the new address
    And my old address is deactivated for login
    And every active session for the account on every device is invalidated immediately
    And I see a confirmation that the change has been applied
    And I am directed to log in afresh with the new address

  Scenario: Email change verification link is opened after the 24-hour window
    Given a verification link was sent to the new address more than 24 hours ago
    When I click the expired link
    Then I see a message that the link has expired
    And my account email remains the original address
    And I am offered a way to request the change again

  Scenario: Email change verification fails because the new address was claimed in the meantime
    Given I have requested an email change to "alice2@example.com" and the verification link has not yet been used or expired
    And in the meantime "alice2@example.com" has been claimed by another account — either a separate signup that verified first or another email-change verification that landed first
    When I click my verification link
    Then I see an error stating that "alice2@example.com" is now registered to another account
    And my account email remains the original address
    And no session is invalidated
    And I am offered a way to request the change again with a different address

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

  # ----- Change password while logged in -----

  Scenario: Successful password change kicks all other sessions
    Given I have at least one other active session for this account on a different device
    When I submit my correct current password and a new password that is at least 12 characters and not in the HaveIBeenPwned breach corpus
    Then my password is updated
    And I remain logged in on the current device
    And every other active session for this account is invalidated immediately
    And any pending email-change verification token for this account is invalidated, per ADR 0012
    And I see a confirmation that the change is complete

  Scenario: Password change while an email change is pending invalidates the pending verification token
    Given I have a pending email-change verification token outstanding for "alice2@example.com"
    And the verification link has not yet been used or expired
    When I successfully change my password from the Account settings page
    Then my password is updated
    And the pending email-change verification token for "alice2@example.com" is invalidated
    And clicking the old verification link no longer changes my account email

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
    And a deletion-confirmation email is sent to the address that was my account email immediately before deletion (per ADR 0011), exempt from the unified rate limit

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
