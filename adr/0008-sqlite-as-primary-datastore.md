# ADR 0008: SQLite as the Primary Datastore

- **Status:** Accepted
- **Date:** 2026-04-16
- **Deciders:** Kira
- **Supersedes:** —
- **Superseded by:** —
- **Related:** ADR 0007, `specs/09-data-model.md`, `specs/00-overview.md`

## Context

ADR 0007 established the v0 backend as Spring Boot on a single VPS and named SQLite as the storage engine within a broader stack decision. This ADR pulls the persistence choice out into its own decision record so it can be cited, superseded, or revisited independently of the Spring Boot / Hetzner choice.

The projected v0 load is tiny: two users on day one, a handful of members if a third shared space opens later, tens of thousands of cached TMDB titles, and hundreds of thousands of `WatchEvent` rows over the product's lifetime. Peak write rate is on the order of **one write per second**, bursting higher only during imports (deferred — see `specs/04-watchlists.md`). Read patterns are list-scroll + detail-page + periodic polling (ADR 0005). There is no analytics workload, no fan-out messaging, no full-text-heavy search in v0.

Running a client-server database (Postgres, MySQL) against this load would mean owning a second process, a second backup story, and a second set of tuning knobs for no measurable benefit. SQLite's documented sweet spot — single-host, modest concurrent writers, well under 1 TB, file-level portability — matches the product exactly.

## Decision

**Use SQLite as the primary and only persistent datastore for v0. One file, WAL mode, foreign keys on, served in-process by the Spring Boot application via `sqlite-jdbc` with Spring Data JDBC.**

Concretely:

- **One database file** at `/var/lib/entlib/entlib.sqlite` on the VPS. No sharding, no replicas, no read-only mirrors.
- **WAL mode** (`PRAGMA journal_mode = WAL`) for non-blocking readers alongside a single writer.
- **Foreign keys on** (`PRAGMA foreign_keys = ON`) — SQLite does not enforce them by default.
- **`synchronous = NORMAL`** under WAL — crash-safe for our threat model (VPS reboot, kernel panic), and meaningfully faster than `FULL`.
- **Busy timeout** set to 5 seconds so the writer does not spuriously error under brief read contention.
- **Schema migrations via Flyway**, SQL-only, versioned under `src/main/resources/db/migration`. Schema follows `specs/09-data-model.md`.
- **In-process access.** Spring Boot opens the file directly via the JDBC driver; no sidecar, no LiteFS, no rqlite in v0.
- **Online backups** via `.backup` under WAL, encrypted and shipped to Backblaze B2 (already defined in ADR 0007).
- **No ORM.** Spring Data JDBC only — explicit mapping, no Hibernate-on-SQLite dialect quirks.

The SQLite file is the single source of truth for every entity described in `specs/09-data-model.md`. Cached TMDB metadata lives in the same file as user-owned rows; a single backup covers everything.

## Rationale

- **Matches scope.** Two to a handful of users, one writer at a time, a few hundred writes per minute at peak. SQLite under WAL is famously over-provisioned for this.
- **One file, one backup, one mental model.** The entire operational story for persistence is "copy a file." Restore is `scp` + restart. There is no second daemon to supervise, secure, or upgrade.
- **Embedded means fast.** In-process JDBC calls skip the TCP round-trip Postgres would demand. On a single-host deployment this is the cheapest latency we can buy.
- **No vendor lock-in.** SQLite is public domain, ubiquitous, and readable by every language and every analytics tool. Migrating to Postgres later is a schema port, not a rewrite — the SQL in Flyway migrations is deliberately kept portable.
- **User-facing export becomes trivial.** Because the database *is* a file, we can offer users a scoped SQLite download as the canonical export format (ADR 0009) rather than building a JSON/CSV pipeline.
- **Matches the team.** One person (so far) operating the stack. Fewer moving parts is better.

## Alternatives considered

- **PostgreSQL on the same VPS.** The default choice for "real" apps. Genuinely competitive — richer types, better concurrency, first-class full-text search. Rejected for v0 because it adds a second daemon, a second backup target, and zero measurable benefit at two users. The door to migrate remains open and cheap; see Follow-ups.
- **MySQL / MariaDB.** No advantage over Postgres for this workload, weaker feature set, same operational cost. Rejected.
- **DuckDB.** Wonderful for analytics, weaker for the OLTP mixed workload we actually have (list reorders, per-user writes, short reads). Rejected as a general-purpose datastore; may revisit for a future stats/rollup job.
- **Managed Postgres (Supabase, Neon, RDS).** Solves the "who runs the DB" problem but reintroduces the monthly bill ADR 0007 was trying to avoid and adds a network round-trip to every query. Rejected for v0.
- **LiteFS / rqlite / Turso.** Distributed SQLite variants. Attractive if we ever need multi-region or multi-writer, but v0 runs on one VPS — the extra machinery is pure cost right now. Noted for post-v0.
- **A document store (MongoDB, DynamoDB).** The data model in `specs/09-data-model.md` is deeply relational — shared lists, watch events, ratings, invites. Document stores would fight the shape of the data. Rejected.

## Consequences

### Positive

- Operational surface is one file and one process.
- Backups are trivial and tested (`sqlite3 .backup` under WAL is the canonical path).
- Developer loop is fast: tests run against an in-memory or temp-file SQLite with the same schema as prod.
- Enables a first-class user export format (ADR 0009).
- Zero marginal monthly cost for the datastore.

### Negative

- **Single writer.** SQLite serializes writes. At our scale this is invisible; if the user base grew to dozens of concurrent writers on the same shared list it would show up as contention. Mitigation: keep write transactions short; revisit when concurrency becomes real.
- **No horizontal scaling of the app tier.** Multiple Spring Boot instances cannot safely share one SQLite file over a network. If we ever need to scale out, we migrate to Postgres (documented path).
- **Weaker full-text search than Postgres.** SQLite FTS5 is good but not as rich as `pg_trgm` + `tsvector`. Acceptable for v0 search needs.
- **Backup is file-level, not query-level.** Point-in-time recovery below a day's granularity would require shipping WAL segments; we've deliberately not set that up for v0.
- **Schema evolution on a live file requires care.** Some `ALTER TABLE` patterns that Postgres handles online require a table-rebuild in SQLite. Flyway migrations must be written with this in mind.

### Follow-ups

- Document the "when do we migrate to Postgres" triggers, matching ADR 0007's cut-over list: >5 active users, or >100k `WatchEvent` rows, or felt latency on common queries, or a need for multi-writer / multi-region.
- Add a CI check that every Flyway migration parses under both SQLite and Postgres (to keep the migration path cheap).
- Write a runbook entry: "restore from backup" (already listed under ADR 0007 follow-ups).
- Decide on the export scoping rules in ADR 0009.

## Sources

- [SQLite — Appropriate Uses For SQLite](https://www.sqlite.org/whentouse.html)
- [SQLite — Write-Ahead Logging](https://www.sqlite.org/wal.html)
- [SQLite — PRAGMA statements](https://www.sqlite.org/pragma.html)
- [Spring Data JDBC reference](https://docs.spring.io/spring-data/relational/reference/jdbc.html)
