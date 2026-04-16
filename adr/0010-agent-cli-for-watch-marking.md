# ADR 0010: Ship an Agent-Facing CLI for Filling Watch Entries

- **Status:** Proposed
- **Date:** 2026-04-16
- **Deciders:** Kira
- **Supersedes:** —
- **Superseded by:** —
- **Related:** ADR 0007, ADR 0008, ADR 0009, `specs/05-watch-tracking.md`, `specs/01-accounts-and-sharing.md`, `specs/09-data-model.md`

## Context

`specs/05-watch-tracking.md` puts the entire watch-tracking model on the user: every episode, every movie, every "finished Season 2" is a manual tap. That design is honest — there is no Netflix-account-link — but it has a predictable failure mode. After a long week the couple finishes three episodes of *Severance* and does not reach for the app. Two weeks later the "Continue watching" rail is lying to them about where they are, and the yearly wrap-up undercounts everything.

Separately, the wider ecosystem has shifted: LLMs with tool use (Claude, GPT, etc.) and voice assistants are now a plausible front-end for quick, conversational updates. "Hey, we just finished *Poor Things* — mark it watched for both of us and rate the vibe 5" is a natural interaction an agent could translate into a handful of state changes. A scheduled agent could also scrape a connected calendar, email receipts from cinemas, or a Letterboxd RSS feed and propose watch marks for approval.

What is missing is a **stable, scriptable entry point** agents can drive without touching the web UI. The v0 web app is a React SPA talking to a REST API (ADR 0005, ADR 0007); nothing prevents an agent from calling that REST API today, but:

- Auth is cookie/session-based, built for browsers (ADR 0007). Agents want a bearer token.
- There is no documented agent contract. An LLM would have to guess endpoints and payloads.
- There is no rate-limiting, auditing, or scoping story tuned for automation.
- There is no local-first path for an agent running on the user's own machine that wants to batch a week of updates.

This ADR records the intent to ship a dedicated CLI as that entry point, and fixes the design envelope. **Implementation is deferred past v0** — no code lands until the web app is live and the watch-tracking flows are stable.

## Decision

**Eventually ship a first-party CLI, `entlib`, that authenticates as a specific user via an API token and exposes the watch-tracking verbs an agent realistically needs: mark watched, mark unwatched, rate, bulk-mark a season, log a rewatch. The CLI is the only supported automation surface in v0.x; direct REST access by agents is explicitly out of contract.**

Concretely, once implemented:

### Scope of actions

The CLI exposes a narrow verb set, matching the watch-tracking primitives in `specs/05-watch-tracking.md`:

- `entlib watch mark <title> [--episode SxEy] [--at TIMESTAMP] [--for-partner]`
- `entlib watch unmark <title> [--episode SxEy]`
- `entlib watch bulk <title> --season N [--through SxEy]` — the binge-session primitive.
- `entlib rewatch log <title> [--at TIMESTAMP]`
- `entlib rate <title> [--story N] [--performance N] [--vibe N] [--entertainment N] [--note "…"]`
- `entlib abandon <title> --reason (pacing|characters|too_heavy|lost_plot|lost_interest|other) [--note "…"]`
- `entlib status <title>` — read-only: where the user is in it.
- `entlib search <query>` — title resolution, returning internal IDs.
- `entlib whoami` — prints the authenticated user.

**Deliberately out of scope for the CLI:** creating lists, managing shared-space membership, changing settings, editing others' data. The verbs above are the ones that benefit from automation; everything else stays in the web UI.

### Authentication and scoping

- Each CLI session authenticates with an **API token** generated from Settings → Security → API Tokens. The token is scoped to the issuing user and carries an explicit set of allowed verbs (defaulting to the watch/rate/abandon verbs above; never to account deletion).
- Tokens are **prefixed** (`entlib_tok_…`) for leak detection, stored server-side as a hash, and display their `last_used_at` and `created_at` in Settings.
- Tokens can be revoked individually from Settings. A revoked token's next request fails with a clear error the CLI surfaces.
- Partner-marking (`--for-partner`) uses the same trust model as the in-app shared-space checkbox (`specs/05-watch-tracking.md`) — the token's user can mark for co-members of the same shared space. No token can act on behalf of a user outside its scope.

### Surface / transport

- The CLI is a **thin client over the existing REST API.** It does not embed business logic; it formats arguments, calls endpoints, and pretty-prints results.
- The same endpoints are reachable directly, but the **CLI is the documented contract.** Endpoint shapes may change between releases; the CLI adapts, agents drive the CLI, agents do not break.
- The CLI reads config from `~/.config/entlib/config.toml` (API base URL, token reference) and supports per-invocation overrides via flags and env vars (`ENTLIB_TOKEN`, `ENTLIB_BASE_URL`).
- Structured output (`--json`) for agent consumption; human-friendly output by default.

### Agent ergonomics

- The CLI ships a `--help` tree detailed enough that an LLM calling `entlib --help` and `entlib <verb> --help` can discover every flag without external documentation. Help text is the spec.
- Every command is **idempotent on equivalent input:** re-running `entlib watch mark poor-things --at 2026-04-15` produces the same `WatchEvent` (dedup by user + episode/title + day, per `specs/05-watch-tracking.md`). Agents retrying on partial failure do not double-log.
- Exit codes are stable: `0` success, `2` user error (bad title, missing flag), `3` auth error, `4` rate-limited, `5` server error. Agents key off these.
- Dry-run flag (`--dry-run`) on every mutating verb: prints the intended change as JSON, writes nothing.

### Auditing and rate limits

- Every CLI call is an `ActivityEvent` in the relevant shared space (`specs/09-data-model.md`) with `actor` = the user and `source` = `cli` vs. `web` so the couple can see what was auto-logged vs. tapped. Added source field is an addition to the `ActivityEvent` entity tracked under Follow-ups.
- Token-scoped rate limits (per token, per minute and per day) cap runaway agents. Limits are generous enough for genuine bulk-marking but tight enough that a misbehaving agent cannot, e.g., spam 1,000 `WatchEvent` rows before anyone notices.

### Distribution

- Ship the CLI as a **single static binary** per platform (darwin/arm64, darwin/amd64, linux/amd64) via GitHub Releases. A `brew tap` is a later convenience.
- Source lives in the same monorepo as the backend (under `cli/`), written in Go for the single-binary story — not Java, to avoid dragging a JRE onto user machines.

### Post-ship lineage

- The CLI is the sanctioned path for **anything** that eventually wants to automate on top of the product: email-receipt ingesters, calendar-driven rewatch scheduling, MCP servers wrapping the CLI as tools, home-automation triggers. All of those flow through the same authentication, auditing, and rate-limiting.

## Rationale

- **The watch-tracking model is only as good as the data in it.** Manual marking will always lag reality. An agent-friendly surface is the simplest way to close the lag without sacrificing the "no streaming-service link" product principle.
- **A CLI is the cheapest agent interface that is still precise.** An LLM-facing UI sounds appealing but smuggles ambiguity into a system of record; a CLI with exit codes and JSON output is exactly what tool-using agents want, and exactly what a skeptical human can audit.
- **Single audited surface.** Routing all automation through one CLI means one place to put rate limits, audit logs, and feature flags. Compare to the sprawl of "which endpoints did which agent hit" if we simply documented REST.
- **Decouples the frontend's freedom from the agent contract.** The web app can rewire its fetch patterns, server-render differently, or migrate to a GraphQL edge without breaking the CLI's stable surface.
- **Matches the data model well.** The verbs in `specs/05-watch-tracking.md` map cleanly onto command-line verbs. The CLI is small.

## Alternatives considered

- **Document the REST API and call it a contract.** Cheapest, but means every endpoint refactor is a breaking change for agents. Also surfaces auth details (cookies, CSRF) that are awkward for bots. Rejected as the primary contract; the REST endpoints remain internal.
- **Build an MCP server directly.** An MCP (Model Context Protocol) server would give Claude / Claude Code / other MCP hosts tool access out of the box. Appealing — and likely a second-order artifact — but MCP is one consumer; a CLI covers MCP (wrap it) plus cron jobs, scripts, voice shortcuts, and ad-hoc debugging. Decision: ship the CLI first, and ship an MCP server later that shells out to the CLI.
- **Ship an SDK (TypeScript / Python).** Good for programmer-assisted automation, weaker for LLM-driven tool use (LLMs handle CLIs with `--help` text more naturally than reading API reference docs mid-prompt). The CLI can coexist with a thin SDK later.
- **Browser extension that observes streaming sites.** Tempting — automatic detection of "Kira just finished S2E5 on Netflix" — but fragile, privacy-adjacent, and a large product deviation. Rejected.
- **Embed a chat input in the web app that calls an LLM.** Solves "natural language entry" but not "agent-to-app." Different problem; could ship later as an LLM that, under the hood, calls the CLI.

## Consequences

### Positive

- A clean, single boundary for automation. Every automated watch mark is auditable, rate-limited, and token-scoped.
- The CLI doubles as a **developer tool** for operators and for QA — the team uses the same verbs as agents.
- Stable surface insulates agents from web-app refactors.
- Opens a cheap path to MCP / voice / home-automation integrations later, each built on the same verbs.
- Keeps the "no streaming-service link" product principle intact: automation is always user-initiated (they run the agent, they issued the token), never a silent third-party sync.

### Negative

- **Scope creep risk.** Once a CLI exists, every feature request ("can I create a watchlist from the CLI?") will be in scope. Mitigation: the ADR pins the verb set to watch-tracking + ratings + abandonment; additions require a follow-up ADR.
- **Second artifact to build, test, release, and document.** Non-trivial ongoing cost even though the implementation is thin. Mitigation: defer to post-v0; do not start until the web app is out of flux.
- **Token leakage is a new security surface.** An agent running on a compromised machine leaks a user-scoped token. Mitigation: prefixed tokens, `last_used_at` surfaced in the Settings token list, one-click revocation, scoped permission set (no account deletion), and per-token rate limits. Deliberately **no** "new token used" notification — that would resurrect the suspicious-activity story the product dropped in v0; the user checking the token list covers this instead.
- **Idempotency requires real care.** Agents retry. Missing a dedup case means silent double-logs and skewed stats. Mitigation: the dedup logic in `specs/05-watch-tracking.md` (`user + episode + day`) is already the right primitive; the CLI tests it explicitly with retry scenarios.
- **Partner-marking via CLI has trust implications.** A token scoped to Kira can mark watched for M inside the "Kira & M" space, just like the in-app checkbox. Acceptable because it mirrors the web trust model, but worth documenting prominently.

### Follow-ups

- Not before v0 ships. The web app and watch-tracking flows must be stable first.
- Design the API-token storage in the `User` / `AuthIdentity` area of `specs/09-data-model.md` (a new `ApiToken` entity: `id`, `userId`, `hashedSecret`, `prefix`, `scopes[]`, `createdAt`, `lastUsedAt`, `revokedAt`).
- Add a `source` field to `ActivityEvent` (`web` | `cli` | future `mcp`) so the shared-space feed distinguishes automation from manual action.
- Draft a spec file (`specs/10-agent-cli.md`) with the user-facing scenarios: "Kira connects Claude to log her binge," "an evening script logs the couple's watches from a shared calendar."
- Build a minimal MCP server on top of the CLI as a separate, later artifact.

## Sources

- `specs/05-watch-tracking.md` — the verb set the CLI mirrors
- `specs/01-accounts-and-sharing.md` — the shared-space trust model the CLI inherits
- `specs/09-data-model.md` — `WatchEvent`, `Rating`, `AbandonReason`, `ActivityEvent` entities the CLI writes to
- [Model Context Protocol specification](https://modelcontextprotocol.io/)
- [GitHub CLI — design notes](https://cli.github.com/manual/) (a precedent for a first-party CLI that is also the contract)
