# E2E smoke test

Drives the canonical user journey from `NEXT.md` against a fully local stack:
a freshly-built `entlib` binary serves the embedded SPA over a temp sqlite,
TMDB is replaced by an in-test stub via `ENTLIB_TMDB_BASE_URL`, and Playwright
drives a real Chromium through the browser.

Runs separately from the unit tests (`pnpm test` stays jsdom-only).

## Prerequisites

```sh
make build                              # builds the entlib binary at repo root
pnpm --filter ./frontend exec playwright install chromium
```

## Required env vars

Credentials are not hardcoded. Provide them per run:

| Variable                           | Purpose                              |
| ---------------------------------- | ------------------------------------ |
| `ENTLIB_E2E_USER_PRIMARY_PASSWORD` | password seeded for the primary user |
| `ENTLIB_E2E_USER_PARTNER_PASSWORD` | password seeded for the partner user |

Both must be at least 14 characters (the backend's `seed` minimum).

Optional overrides:

| Variable                  | Default     | Purpose                         |
| ------------------------- | ----------- | ------------------------------- |
| `ENTLIB_E2E_USER_PRIMARY` | `kira`      | username for the primary user   |
| `ENTLIB_E2E_USER_PARTNER` | `partner`   | username for the partner user   |
| `ENTLIB_BIN`              | `../entlib` | path to a built `entlib` binary |

## Run

```sh
ENTLIB_E2E_USER_PRIMARY_PASSWORD=$(openssl rand -base64 24) \
ENTLIB_E2E_USER_PARTNER_PASSWORD=$(openssl rand -base64 24) \
pnpm --filter ./frontend test:e2e
```
