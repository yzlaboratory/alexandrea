# Create an account

A new visitor signs up using an email address and a password. The account is
created in an unverified state; a verification email is sent and must be
acknowledged before the user can log in. This is the only sign-up path in v1
(see `docs/OOS.md` for deferred OAuth and magic-link options). The password
policy follows ADR 0002 (NIST 800-63B): minimum 12 characters and not present
in the HaveIBeenPwned breach corpus. All emails are sent from the single
`noreply@<domain>` sender and counted against the unified per-recipient rate
limit defined in ADR 0011 (1/min, 5/hr, shared across every flow).

```gherkin
Feature: Create an account
  As a new visitor
  I want to create an account using my email and a password
  So that I can keep a private watchlist and library

  Background:
    Given I am on the create-account page

  Scenario: Successful account creation triggers email verification
    When I submit a valid email address and a password that is at least 12 characters and not in the HaveIBeenPwned breach corpus
    Then I see a confirmation that a verification link has been sent to my email
    And I am not yet logged in
    And clicking the verification link in the email within 24 hours marks my account as verified
    And I am redirected to the login page after verification

  Scenario: Email already in use
    Given an account already exists for "ada@example.com"
    When I submit "ada@example.com" with any password
    Then I see an error stating the email is already registered
    And no second verification email is sent
    And no account is created

  Scenario Outline: Email comparison is case-insensitive
    Given an account already exists for "ada@example.com"
    When I submit "<typed>" with any otherwise-valid password
    Then I see an error stating the email is already registered
    And no account is created
    And no verification email is sent

    Examples:
      | typed             |
      | ADA@EXAMPLE.COM   |
      | Ada@Example.com   |
      | ada@EXAMPLE.com   |

  Scenario: Password is shorter than 12 characters
    When I submit a valid email and a password shorter than 12 characters
    Then I see an error stating the password must be at least 12 characters
    And no account is created
    And no verification email is sent

  Scenario: Password appears in the HaveIBeenPwned breach corpus
    When I submit a valid email and a 12+ character password that has been seen in a known data breach
    Then I see an error stating the password has been seen in a public data breach and asking me to choose another
    And no account is created
    And no verification email is sent

  Scenario: Malformed email is rejected
    When I submit a string that is not a valid email address
    Then I see an error stating the email is invalid
    And no account is created
    And no verification email is sent

  Scenario: Verification link is opened after the 24-hour window
    Given a verification link was sent to "ada@example.com" more than 24 hours ago
    When the recipient clicks the expired verification link
    Then they see a message that the link has expired
    And they are offered a way to request a new verification email

  Scenario: Logging in before verification is blocked
    Given I have created an account but not yet clicked the verification link
    When I attempt to log in with my email and password
    Then I am told my email must be verified before logging in
    And I am offered a way to resend the verification email

  Scenario: Resend-verification rate limit (unified bucket per ADR 0011)
    Given I have just received any email at "ada@example.com" from this service in the last minute
    When I request another verification email for the same address
    Then the request is rejected with a "please wait before requesting another email" message
    And no second email is sent

  Scenario: Resend-verification hourly cap (unified bucket per ADR 0011)
    Given "ada@example.com" has received 5 emails from this service within the past hour, across any combination of flows (verification, password reset, email change, etc.)
    When I request a 6th email of any flow for that address within that hour
    Then the request is rejected with a "you have requested too many emails recently" message
    And no email is sent
```
