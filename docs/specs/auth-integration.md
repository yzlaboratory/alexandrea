# How the library consumes kiraauth

The entertainment library does **not** own authentication. Identity,
credentials, sessions, and the email flows around them are owned by
**kiraauth** — a separate backend service running on its own
infrastructure, on its own domain. This spec captures only what the
library expects from kiraauth and what it does in response. The auth
flows themselves (signup, login, password change, email change,
account deletion) live in `kiraauth/docs/specs/`.

The integration mechanics — exactly *how* a library request resolves
to a User ID, exactly *how* kiraauth notifies the library of a
deletion — are TBD until kiraauth ADR 0004 closes its open questions.
This spec describes the contract in terms that survive any of the
candidate mechanics; specific endpoint shapes will be added once
the mechanics are pinned.

```gherkin
Feature: Consume kiraauth as the library's authentication backend
  As the entertainment library
  I want to defer all identity, credential, and session work to kiraauth
  So that I never store passwords, emails, or session tokens, and the user has one canonical login

  Background:
    Given kiraauth is reachable as a backend service
    And the library knows the kiraauth User ID of every per-user row it owns

  # ----- Anonymous traffic -----

  Scenario: Anonymous visitor lands on a minimal home page
    Given the visitor's user agent presents no kiraauth Session ID, or one that does not resolve
    When the visitor opens the root URL of the library
    Then the library serves a minimal landing page consisting of an app pitch, a "Sign up" call-to-action, and a "Log in" link
    And the library does not show any catalog preview, any user data, or any media-type navigation

  Scenario: Anonymous visitor on a protected URL is redirected to kiraauth login
    Given the visitor's user agent presents no kiraauth Session ID, or one that does not resolve
    When the visitor opens a URL that requires a session — Catalog, Watchlist, Library, Shares tab, Account settings, or any deep-linked detail overlay over those surfaces
    Then the library redirects to kiraauth's login page
    And the original destination is preserved so the visitor lands on it after a successful login
    And the library does not gate any of these flows itself — kiraauth is the only gate

  Scenario: Anonymous visitor on a Share URL is not redirected
    Given the visitor's user agent presents no kiraauth Session ID, or one that does not resolve
    And an active Share URL exists
    When the visitor opens the Share URL
    Then the library serves the Share view as defined in share-top-rated.md
    And the library does not redirect to kiraauth's login page

  # ----- Authenticated traffic -----

  Scenario: A request bearing a valid Session ID resolves to a User ID
    Given the user agent presents a kiraauth Session ID that kiraauth resolves to a User ID
    When the user agent makes any authenticated request to the library
    Then the library treats that User ID as the actor on the request
    And the library uses that User ID as the foreign key when reading or writing per-user rows

  Scenario: Successful login lands on last-used media type's catalog browse
    Given a User has just completed a successful login through kiraauth
    And the User's last-used media type is "Books"
    And the User's session has been established
    When the User is redirected back to the library
    Then the library lands them on the Books catalog browse page
    And the library shows a two-level navigation: media types (Movies, TV, Books, Games) at the top, and surface tabs (Catalog, Watchlist, Library, Shares) within the selected media type
    And an account menu offers a link out to kiraauth's Account settings page and a logout action

  Scenario: First-ever login defaults to Movies catalog
    Given a User has just completed a successful login through kiraauth
    And the User has never selected a media type before
    When the User is redirected back to the library
    Then the library lands them on the Movies catalog browse page

  Scenario: Switching media type updates the per-user sticky preference
    Given the User is logged in and viewing the Movies catalog
    When the User switches to the TV media type via the top-level navigation
    Then the library updates the User's sticky media type to TV server-side
    And the next time the User logs in from any device they land on the TV catalog browse page

  Scenario: Logout originates from the library and is delegated to kiraauth
    Given the User is logged in
    When the User clicks "Log out" in the library's account menu
    Then the library redirects the User into kiraauth's logout flow
    And the library does not maintain its own session state to invalidate

  Scenario: Account settings is a link out to kiraauth
    Given the User is logged in
    When the User clicks "Account settings" in the library's account menu
    Then the library navigates the User to kiraauth's Account settings page
    And the library does not host email-change, password-change, or delete-account forms of its own

  # ----- User-deleted notification -----

  Scenario: User-deleted notification triggers a hard cascade
    Given the library is registered with kiraauth as a consuming app
    When kiraauth fires a user-deleted notification carrying a User ID
    Then the library hard-cascades every row it holds for that User ID, per ADR 0016
    And the library acknowledges the notification to kiraauth
    And subsequent Share URLs whose Owner was that User resolve to the "no longer active" terminal message, per ADR 0016

  Scenario: A duplicate user-deleted notification is a no-op
    Given the library has already cascaded for a given User ID in response to an earlier notification
    When kiraauth retries or re-delivers the same user-deleted notification for that User ID
    Then the library acknowledges the notification without re-running any cascade logic
    And the library does not error on the missing rows
```

## Open questions

- **Session resolution mechanics.** Per kiraauth ADR 0004, the
  library API may resolve a kiraauth Session ID via a shared
  parent-domain cookie + introspection, an opaque-token
  introspection POST, or an OIDC-style ID token. The choice
  affects: which CloudFront origin the library API lives on
  (subdomain of the kiraauth parent vs. unrelated domain), what
  CSRF posture the library API takes (per the rewritten ADR 0014
  consequence), and the per-request latency of authentication.
  Add a follow-up spec section once kiraauth ADR 0004 closes.
- **User-deleted notification transport.** Per kiraauth ADR 0004,
  this is either an outbound webhook from kiraauth or a polled
  deletion log on kiraauth. The library handler must be
  idempotent either way (per ADR 0016); the open question is
  whether the library exposes an HTTP endpoint or runs a poll
  loop.
- **Logout UX.** "Click logout in the library and end up on
  kiraauth's logout-confirmation page" is honest but jarring.
  An alternative — kiraauth performs the logout silently and
  redirects the user back to the library's home — is friendlier
  but assumes a redirect contract that lives in kiraauth ADR
  0004's open questions. Defer.
