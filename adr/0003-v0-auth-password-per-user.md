# ADR 0003: v0 Auth — Password Per User, Server-Side Sessions

- **Status:** Accepted
- **Date:** 2026-04-17
- **Deciders:** Kira
- **Supersedes:** —
- **Superseded by:** —
- **Related:** ADR 0001, ADR 0002, `specs/04-data-model.md`

## Context

The product specs deliberately punt authentication to the deployment layer (see `specs/04-data-model.md`, §`User`: *"The authentication method is a deployment concern…and is deliberately outside the product spec."*). Two user identities exist in the product (`User.display_name` = "Kira", "M"); the auth layer must map an incoming request to one of them so that ratings and library-entry attribution work.

The app is reachable on the open internet behind TLS (ADR 0001), runs as a single Go process (ADR 0001) against SQLite (ADR 0002). There is no email sender in scope, no SSO provider, no intranet/VPN boundary.

## Decision

Use a **classic password-per-user login** with **server-side sessions** stored in SQLite.

Concretely:

- **Hashing:** `argon2id` via `golang.org/x/crypto/argon2`, parameters per current OWASP guidance (memory 64 MiB, time 3, parallelism 2, 32-byte salt, 32-byte key). The encoded hash string stores the parameters inline so we can tune later without breaking existing hashes.
- **Credentials table** (in SQLite, separate from the product-facing `user` table so the product spec stays clean):
  ```
  user_credential(
      user_id     TEXT PRIMARY KEY REFERENCES user(id) ON DELETE CASCADE,
      password_hash TEXT NOT NULL,
      updated_at  TEXT NOT NULL
  )
  ```
- **Session table** (also separate, deployment-layer):
  ```
  session(
      id           TEXT PRIMARY KEY,   -- opaque 256-bit random, base64url
      user_id      TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
      created_at   TEXT NOT NULL,
      last_seen_at TEXT NOT NULL,
      expires_at   TEXT NOT NULL
  )
  ```
- **Login flow:** POST `/login` with `username` + `password`. On success, insert a `session` row and set a cookie. On failure, constant-time password comparison, generic error message ("sign-in failed"), and rate-limit (below).
- **Cookie:** `ENTLIB_SESSION`, `HttpOnly`, `Secure`, `SameSite=Lax`. Expiry 90 days sliding — every authenticated request that's more than a day past `last_seen_at` extends the row.
- **Logout:** deletes the `session` row and clears the cookie.
- **Rate limit:** per-username exponential backoff on failed logins (1 s, 2 s, 4 s, …, capped at 60 s), reset on success. Plus a global per-IP cap of ~30 failed attempts in 10 minutes, to make the open-internet footprint less appealing to crawlers.
- **CSRF:** every state-changing request (including `/login`) requires a per-session CSRF token sent as a header and echoed from a cookie. Concrete pattern (double-submit cookie, ~30 lines of middleware) is specified in ADR 0005.
- **User seeding:** the Go binary grows a `seed` subcommand, invoked once on the VPS:
  ```
  entlib seed --display-name Kira --username kira --password '...'
  entlib seed --display-name M    --username m    --password '...'
  ```
  Running `seed` for an existing username rotates the password in place.
- **Password rotation UI:** not in v0. Rotation goes through `entlib seed` on the server.

## Rationale

- **Works anywhere.** No email provider, no SSO IdP, no VPN — drops straight onto the single-VPS shape from ADR 0001.
- **Argon2id over bcrypt.** OWASP's current recommendation; the Go stdlib-adjacent `x/crypto` has a maintained implementation; encoded strings carry their own parameters so we can raise cost later without migrating.
- **Server-side sessions over signed cookies / JWTs.** Revocable (deleting a session row is instant logout everywhere), no JWT footguns (alg confusion, replay windows, etc.), and the "session" table is literally ~50 rows over the product lifetime. Stateless tokens buy us nothing at this scale.
- **Separate `user_credential` table.** Keeps `specs/04-data-model.md` pristine — the product doesn't know or care that passwords exist. If we replace auth later, the product schema doesn't move.
- **Single-binary subcommands for seeding.** Matches ADR 0001's "one binary" aesthetic: no separate admin tool, no `psql`-equivalent to learn.

## Alternatives considered

- **Magic links via email.** Requires an SMTP dependency (Resend/Mailgun free tier) on the critical path for logging in, plus template and link-handling code. Rejected for the MVP; revisit if user count grows.
- **Tailscale in front, header-based identity.** Elegant zero-auth-in-the-app shape, but forces both users onto the tailnet for access, and introduces a vendor dependency. Rejected because "reachable from the open internet" was the stated constraint.
- **HTTP Basic auth at Caddy.** No login UI, Caddy handles creds. Rejected because the browser's Basic-auth prompt is an ugly UX and there's no clean logout.
- **OAuth with Google/Apple.** Overkill for two fixed users; adds client-registration ceremony for a product that explicitly has no signup flow.
- **Passkeys / WebAuthn.** Nicest UX of all of these, but meaningfully more code, device-registration flows, and fallbacks. Worth revisiting when more users arrive.
- **Signed cookies / JWTs instead of a session table.** Stateless is fashionable but earns no benefit at one app server, and costs us revocation. Rejected.
- **Bcrypt instead of argon2id.** Still acceptable, but argon2id is the 2020s default; no reason to choose the older primitive for new code.

## Consequences

### Positive
- No external auth dependency. Deploy works with nothing but the binary, SQLite file, and systemd.
- Revocable sessions. Logging out of one device logs out that device; manual row deletion logs out everyone.
- The product data model stays auth-agnostic — future migration (to SSO, passkeys, magic links) touches only `user_credential`, `session`, and a handful of HTTP handlers.
- Seeding is a one-liner on the VPS.

### Negative
- **No MFA in v0.** Acceptable for two users where the threat model is "some bot on the internet, or a curious friend." If the product ever opens up, MFA becomes mandatory.
- **No self-service password reset.** A forgotten password means ssh-ing into the VPS and running `entlib seed`. Acceptable for two users, painful at three or more.
- **We own a login UI.** A small one, but a UI nonetheless — more surface than Tailscale-fronted or SSO would leave.
- **Brute-force posture is modest.** The rate limits above make the app a poor target but not an impossible one. A determined attacker with many IPs could still crawl attempts; the mitigation is a strong password per user.

### Follow-ups
- **Password length floor: ≥14 chars, no other complexity rules** (decided 2026-04-18). Aligns with NIST 800-63B; length beats character-class rules. Document in `docs/runbooks/seed-users.md` when that file exists; optionally add zxcvbn-style strength feedback later.
- Tune argon2id parameters after measuring login latency on the CX22 — 250–500 ms is the target band.
- When a third user arrives, revisit whether password-reset-via-ssh is still tolerable or we need a recovery flow.
- Add a simple admin page listing active sessions (view + revoke) — nice to have, not v0.

## Sources

- [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
- [`golang.org/x/crypto/argon2`](https://pkg.go.dev/golang.org/x/crypto/argon2)
- [RFC 6265 — HTTP Cookies](https://www.rfc-editor.org/rfc/rfc6265)
- [OWASP Session Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)
