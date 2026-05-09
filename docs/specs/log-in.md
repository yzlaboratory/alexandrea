# Log in, log out, and reset password

A returning user logs in with email and password, lands on the catalog
browse for their last-used media type (defaulting to Movies for new users),
logs out, and recovers access via an email-based password reset. Bundled
into one spec because all three flows share the same authentication
surface and lifecycle. Session lifetime, password-reset link expiry,
failed-login throttling, and post-login navigation are all v1-decided
values; see ADR 0002 for the password policy, ADR 0011 for the
cross-cutting email policy (single sender, unified per-recipient rate
limit) that the password-reset email obeys, and ADR 0012 for the
session-invalidation behaviour on password reset.

HIBP fail-open behaviour on password reset is per ADR 0002: if the
HaveIBeenPwned breach-check API is unreachable, a new password that
meets the local 12-character rule is accepted without breach screening,
silently. The Gherkin below describes the intended steady-state
behaviour.

```gherkin
Feature: Log in, log out, and reset password
  As a returning user
  I want to log in to access my watchlists and libraries, log out when done, and recover access if I forget my password
  So that my data stays private to me

  Background:
    Given my account exists and my email is verified

  Scenario: Successful login lands on last-used media type's catalog browse
    Given my last-used media type is "Books"
    And I am on the login page
    When I submit my correct email and password
    Then I am redirected to the Books catalog browse page
    And I see a two-level navigation: media types (Movies, TV, Books, Games) at the top, and surface tabs (Catalog, Watchlist, Library, Shares) within the selected media type
    And an account menu offers Account settings and logout

  Scenario: First-ever login defaults to Movies catalog
    Given I have never selected a media type before
    And I am on the login page
    When I submit my correct email and password
    Then I am redirected to the Movies catalog browse page

  Scenario: Switching media type updates the per-user sticky preference
    Given I am logged in and viewing the Movies catalog
    When I switch to the TV media type via the top-level navigation
    Then my sticky media type is updated to TV server-side
    And the next time I log in from any device I land on the TV catalog browse page

  Scenario: Wrong password
    Given I am on the login page
    When I submit my correct email but a wrong password
    Then I see a generic error stating the credentials are invalid
    And I am not logged in

  Scenario: Unknown email
    Given I am on the login page
    When I submit an email that has no account
    Then I see the same generic "credentials are invalid" error as for a wrong password
    And I am not logged in

  Scenario: Failed-login throttle uses per-IP exponential backoff capped at 30 seconds
    Given I am on the login page
    When the same client IP submits incorrect credentials three times in a row, regardless of which email each attempt targeted
    Then each subsequent failed attempt from that IP is delayed before responding by an exponentially increasing wait — 1s, 2s, 4s, 8s, 16s — then capped at 30s for every further failed attempt
    And the per-IP backoff resets to zero on either a successful login from that IP or one hour with no failed attempts from that IP

  Scenario: Per-email coarse slowdown defends against credential stuffing across many IPs
    Given more than 50 failed login attempts have been recorded against "ada@example.com" within the past hour, regardless of which IPs they came from
    When any further login attempt is made for "ada@example.com" — from any IP, including a brand-new one
    Then the response is delayed so that the email never accepts more than 1 attempt per minute until either a successful login for "ada@example.com" or 60 consecutive minutes pass with no failed attempts against that email
    And the email is never locked out — every attempt is eventually answered, only delayed
    And legitimate fat-fingering by the account owner that does not exceed 50 failures per hour does not trigger this slowdown

  Scenario: Sliding 30-day session
    Given I am logged in
    And I have not interacted with the app for fewer than 30 days
    When I make any authenticated request
    Then my session is renewed and remains valid for another 30 days from now

  Scenario: Session expires after 30 days of inactivity
    Given I am logged in
    And I have not interacted with the app for more than 30 consecutive days
    When I attempt to access any page that requires a session
    Then my session is treated as expired
    And I am redirected to the login page

  Scenario: Log out
    Given I am logged in
    When I log out
    Then I am redirected to the login page
    And visiting any page that requires a session sends me back to the login page

  Scenario: Anonymous visitor lands on a minimal home page
    Given I am not logged in
    When I open the root URL of the app
    Then I see a minimal landing page consisting of an app pitch, a "Sign up" call-to-action, and a "Log in" link
    And I do not see any catalog preview, any user data, or any media-type navigation

  Scenario: Anonymous visitor on a protected URL is redirected to the login page
    Given I am not logged in
    When I open a URL that requires a session — Catalog, Watchlist, Library, Shares tab, Account settings, or any deep-linked detail overlay over those surfaces
    Then I am redirected to the login page
    And the original destination is preserved so I land on it after a successful login

  Scenario: Anonymous visitor on a Share URL is not redirected
    Given I am not logged in
    And an active Share URL exists
    When I open the Share URL
    Then I see the Share view as defined in share-top-rated.md
    And I am not redirected to the login page

  Scenario: Request a password-reset link
    Given I am on the password-reset request page
    When I submit any email address
    Then I see a confirmation that a password-reset link has been sent if the email is registered
    And the same confirmation appears whether or not the email is registered

  Scenario: Use a password-reset link to set a new password
    Given a password-reset link was sent to my email less than 1 hour ago
    When I open the link and submit a new password that is at least 12 characters and not in the HaveIBeenPwned breach corpus
    Then my password is updated
    And every active session for the account on every device is invalidated immediately
    And I am redirected to the login page
    And the reset link becomes single-use and can no longer be used

  Scenario: Use an expired password-reset link
    Given a password-reset link was sent to my email more than 1 hour ago
    When I open the expired link
    Then I am told the link has expired
    And I am offered a way to request a new reset link

  Scenario: Reset link rejected when new password fails the policy
    Given a password-reset link was sent to my email less than 1 hour ago
    When I open the link and submit a new password that is shorter than 12 characters or appears in the HaveIBeenPwned breach corpus
    Then my password is not updated
    And I see an error explaining which rule was not met
    And the reset link remains usable until either it succeeds once or it expires
```
