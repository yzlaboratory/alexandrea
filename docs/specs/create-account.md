# Create an account

A new visitor signs up using an email address and a password. The account is
created in an unverified state; a verification email is sent and must be
acknowledged before the user can log in. This is the only sign-up path in v1
(see `docs/OOS.md` for deferred OAuth and magic-link options). The password
policy follows ADR 0002 (NIST 800-63B): minimum 12 characters and not present
in the HaveIBeenPwned breach corpus.

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

  Scenario: Resend-verification rate limit
    Given I have just requested a verification email for "ada@example.com"
    When I request another verification email for the same address within the next minute
    Then the request is rejected with a "please wait before requesting another verification email" message
    And no second email is sent

  Scenario: Resend-verification hourly cap
    Given I have already requested 5 verification emails for "ada@example.com" within the past hour
    When I request a 6th verification email within that hour
    Then the request is rejected with a "you have requested too many verification emails recently" message
    And no email is sent
```
