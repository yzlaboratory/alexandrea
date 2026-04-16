# ADR 0009: Offer SQLite as the User Data Export Format

- **Status:** Accepted
- **Date:** 2026-04-16
- **Deciders:** Kira
- **Supersedes:** —
- **Superseded by:** —
- **Related:** ADR 0008, ADR 0007, `specs/00-overview.md`, `specs/08-recommendations-and-history.md`, `specs/09-data-model.md`

## Context

Earlier drafts of the specs promised JSON and CSV data exports as part of a GDPR-adjacent posture. That posture has been removed from v0 (see the updated `specs/00-overview.md`): no age gate, no compliance-driven export, no analytics opt-in flow. The product is a couple-only tool where the data-out-of-the-app story is about **user agency over their own memory** — Kira should be able to take her watch history and ratings with her — not about statutory compliance.

Since ADR 0008 makes SQLite the primary datastore, there is an unusually clean opportunity here: the "export" of a user's data can literally be **a SQLite file** containing their own rows, produced by a scoped dump of the server-side database. This is cheaper to implement than a JSON/CSV pipeline, strictly more useful to anyone technical (a single file queryable with `sqlite3`, Datasette, DuckDB, pandas, or any programming language), and trivially re-importable into a future version of the app.

This ADR defines the export format, what is and isn't included, and the user flow.

## Decision

**Users can download a single-file SQLite export of their personal library from Settings → Data. The file contains the user's own rows plus a read-only snapshot of the catalog entries their data references. It is produced on demand, offered as a direct download, and is the only export format offered in v0.**

Concretely:

### What the export contains

- **The user's own rows:** `User` (their row), `AuthIdentity`, `Session` (optional; see below), `UserStreamingSubscription`, `Watchlist` (personal, plus shared lists they belong to), `WatchlistEntry`, `WatchlistEntryVeto`, `WatchEvent`, `Rating`, `AbandonReason`, `HomepageSnapshot`, `RecommendationSnapshot`, `Notification`, `NotificationPreferences`.
- **Shared-space context the user belongs to:** `SharedSpace`, `SharedSpaceMembership` (all members of the spaces the exporting user is in), `ActivityEvent` (events in their spaces).
- **Referenced catalog slice:** for every `titleId` that appears in any of the above, include the `Title`, `TitleLocalization` in the user's locales, `TitleGenre`, `TitleContentWarning`, `TitleCertification`, relevant `Season` / `Episode` rows, and `StreamingAvailability` entries in the user's region. The catalog slice is a snapshot, not live.
- **An `_export_meta` table** with: `exportedAt` (UTC), `schemaVersion` (matches the Flyway migration number), `sourceAppVersion`, `ownerUserId`, and a short human-readable README blob.

### What is explicitly excluded

- Other users' ratings, watch history, personal watchlists, or auth identities. A shared-space membership means you can see your partner's ratings **through the app**; it does not mean your exported SQLite includes their private rows.
- `SpaceInvite` rows. They carry the invited address the user typed, which is another person's email. Excluded entirely from exports to avoid redistributing third-party PII, even when the exporter is the one who sent the invite.
- Session cookies, TOTP secrets, password hashes (N/A — no email/password in v0 per `specs/01-accounts-and-sharing.md`), or raw OAuth tokens. `Session` rows are included with identifiers only and no secrets; by default `Session` is omitted entirely and can be included with an "include sessions metadata" toggle.
- The full catalog. Exports only carry the titles the user's rows reference.
- Server-internal tables (Flyway history, any background-job queues, rate-limit state).

### How it is produced

- On request, the backend runs a **scoped dump** into a freshly-created temporary SQLite file, using `ATTACH DATABASE` + `INSERT INTO … SELECT …` driven by a deterministic export script, then `VACUUM`s the result.
- The file is streamed to the user as `entlib-export-{userId}-{YYYYMMDD-HHMMSS}.sqlite` with `Content-Type: application/vnd.sqlite3`.
- **No email link, no 72-hour URL.** The download is synchronous for the expected size range (single-digit megabytes even after years of use at couple scale); if a future scale ever pushes past ~50 MB or ~10 s of generation time, switch to an async job with a signed URL (see Follow-ups).
- The export is **rate-limited** to a small number per day per user (suggested: 5) to prevent runaway generation.
- Every export writes an audit row (`ActivityEvent` kind `exported_self`) visible to the user in their own account activity; not broadcast to shared-space members.

### Format guarantees

- Schema mirrors the server schema at the time of export. The `_export_meta.schemaVersion` column tells a future consumer exactly which Flyway version produced it.
- Foreign keys are enabled in the exported file.
- The file is self-contained: every foreign key target referenced in the user's rows is also present in the file, so opening it in `sqlite3` and running joins works without further data.
- Text is UTF-8; timestamps are ISO-8601 in UTC. Dates follow SQLite's standard `TEXT` storage.

### Re-import

Re-import back into the live app is **not supported in v0.** The export is one-way: "get your data out." A future ADR may define a structured import path using this same format as the canonical input.

## Rationale

- **We already speak SQLite.** Producing a scoped SQLite dump is a handful of SQL statements; producing an equivalent JSON tree would be more code and more edge cases (unicode escaping, streaming, schema drift).
- **More useful than JSON/CSV for anyone technical.** The exporter can run queries immediately: `SELECT title.displayTitle, rating.overallScore FROM …`. Tools like Datasette render it instantly in a browser. CSV loses structure; JSON loses queryability.
- **Schema-honest.** The export carries the live schema, not a hand-written contract that will drift. Versioning is explicit via Flyway number.
- **Re-importable in principle.** Even without v0 import support, the format is the one we'd use for a future import path — so nothing is thrown away.
- **Matches the product's character.** A couple's library tool should feel like something Kira owns, not rents. Handing her the database is the most honest version of that.

## Alternatives considered

- **JSON export.** Familiar, easy to consume in JavaScript. Rejected as the sole format because it loses queryability and relational structure; we may still offer it as a second option later if requested, but it is not the primary.
- **CSV export (one file per table, zipped).** Spreadsheet-friendly but verbose and lossy (foreign keys become opaque integers). Noted as a potential future convenience; not the canonical format.
- **PDF / HTML "year in review" export.** Nice as a shareable artifact but not a data export — it is a rendering. Different concern; may happen independently for the wrap-up card in `specs/08-recommendations-and-history.md`.
- **Live API key the user gives to their own tools.** More flexible, but in v0 we have no read API surface scoped to "everything this user has." Building one to solve export would be more work than the scoped dump. Revisit if/when the agent CLI in ADR 0010 materializes; at that point the API surface exists and an export endpoint can ride on it.
- **Full server database dump.** Tempting for simplicity but leaks other users' data. Hard-rejected.

## Consequences

### Positive

- Small, well-defined feature: a few hundred lines of SQL + a REST endpoint + a Settings button.
- No JSON / CSV serializers to maintain.
- Cheap to test: assert the export file opens, has the expected tables, and roundtrips a `SELECT count(*)` per table against the live DB.
- Demonstrable user agency — Kira can show her partner "here's literally everything we have on you" as a file.

### Negative

- **SQLite is less approachable for non-technical users.** A generic end user may not know what to do with a `.sqlite` file. Mitigation: include a plain-English README in `_export_meta` with example `sqlite3` commands, and link a help article. We're two developers using it; this is acceptable.
- **Schema leak.** The export reveals the internal schema. Not a security concern for user data (they own it) but means schema changes need a migration story for exports older consumers may have saved. Mitigation: the `schemaVersion` column lets future tooling key off it.
- **Must stay honest as the schema evolves.** Adding a sensitive column (e.g., a coarse IP for Sessions) means explicitly deciding whether it belongs in exports. Mitigation: a test enumerates "tables/columns excluded from export" and fails if a new column appears without a conscious decision.
- **Not re-importable yet.** A user who exports, deletes their account, and later signs up again cannot click "restore." Acceptable for v0.

### Follow-ups

- Decide the export rate limit and audit-event kind (suggested above, not locked).
- Write the exporter implementation, gated behind a feature flag until the schema has settled past initial migrations.
- Add a golden-file test: given a seeded account, the exported file matches an expected table-row-count fingerprint and passes `PRAGMA foreign_key_check`.
- Write the help-article copy linked from the Settings button.
- Revisit async generation / signed URL delivery if exports ever cross ~50 MB or ~10 s.
- Define a re-import path in a future ADR, using this same format as input.

## Sources

- [SQLite — Backup API / `.backup`](https://www.sqlite.org/backup.html)
- [SQLite — `ATTACH DATABASE`](https://www.sqlite.org/lang_attach.html)
- [Datasette — Publish SQLite databases as an interactive website](https://datasette.io/)
- `specs/09-data-model.md` (entities referenced by the export scope)
- ADR 0008 (SQLite as primary datastore)
