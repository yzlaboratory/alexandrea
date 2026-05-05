# Accessibility target is WCAG 2.1 AA across every surface

Every user-facing surface in the app — Catalog, Watchlist, Library,
Shares tab, Share view, detail overlay, Account settings, login,
sign-up — must conform to **WCAG 2.1 Level AA**. AA is non-negotiable
acceptance criteria for any spec; specs do not need to repeat
generic a11y obligations (color contrast, alt text, semantic
markup, etc.) — they are subsumed by this ADR.

WCAG 2.1 AA is the right level because:

- **Level A is too weak.** A does not require keyboard operability
  for all functionality, color contrast above 3:1, or that text
  can be resized to 200% without loss of content. All three
  matter for a personal tracker that lives entirely in the
  browser.
- **Level AAA is unrealistic for a small team.** It demands sign
  language interpretation for live audio, 7:1 contrast on body
  text, and other obligations that are reasonable for government
  procurement but not for an indie product.
- **AA is the legally-relevant bar in most jurisdictions** (EAA
  2025 in the EU; ADA Title III precedent in the US; AODA in
  Ontario). Targeting it is also targeting compliance.

## Specific obligations worth pinning explicitly

These are AA requirements that are easy to overlook even with the
"we conform to AA" commitment in place. Future engineers and PRs
must satisfy each:

- **Every interaction must be operable with keyboard alone.**
  Every drag-to-dismiss, every swipe, every hover-revealed
  affordance must have a keyboard-equivalent path. The mobile
  bottom-sheet drag-down dismiss must coexist with a visible
  close button; the 1–10 Rating slider must accept numeric input
  alongside any drag interaction.
- **Focus management for the detail overlay.** On open, focus
  moves into the overlay (typically the close button or the
  first interactive element). Focus is **trapped** within the
  overlay — `Tab` and `Shift+Tab` cycle within it, never escape
  to the dimmed grid behind. On dismiss, focus is **restored**
  to the grid item that opened the overlay.
- **The overlay announces itself as a dialog** to assistive
  technologies (`role="dialog"`, accessible name, `aria-modal`).
  The dimmed grid behind is `aria-hidden` while the overlay is
  open.
- **Dynamic state changes are announced.** Toast notifications
  (add to watchlist, remove from watchlist, link copied,
  Completion confirmed, deletion confirmed) must use `role="status"`
  or `role="alert"` so screen readers convey them. Filter-chip
  changes that alter the result set must be announced too.
- **Color is never the sole channel.** The "Removed by <provider>"
  affordance, the disabled state on cleared filters, the
  near-expiry tail on Shares — none of these may rely on color
  alone. Iconography or text labels carry the meaning.
- **Text resize to 200%** must not break layout. The mobile
  bottom-sheet height and the detail-overlay scrolling must
  accommodate enlarged text.

## Consequences

- **Components must be a11y-clean from inception.** Retrofitting
  a focus trap or keyboard parity into a component built without
  them is a rewrite, not a patch. Every PR that introduces a new
  interactive surface must demonstrate AA conformance during
  review.
- **A11y is part of acceptance, not a follow-up.** A spec is not
  "done" if its primary surface fails AA. There is no "ship now,
  fix a11y later" path.
- **Specs stay free of generic a11y boilerplate.** Spec scenarios
  describe user-visible behaviour; AA conformance is implicit.
  Per-spec a11y scenarios are added only when a behaviour has a
  non-generic a11y twist worth pinning (e.g. focus restoration
  semantics for the overlay).
- **Automated AA scanning** (axe-core, Lighthouse, etc.) is
  expected as part of CI. No specific tool is mandated by this
  ADR — pick one that runs on every PR.
