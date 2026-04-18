# ADR 0002: v0 Datastore — SQLite (Pure-Go driver) on Disk

- **Status:** Accepted
- **Date:** 2026-04-17
- **Deciders:** Kira
- **Supersedes:** —
- **Superseded by:** —
- **Related:** ADR 0001

## Context

The MVP has four entities (`User`, `Title`, `LibraryEntry`, `Rating`), two writers (the two seeded users), and a write rate that will be measured in "a handful per day." No full-text search requirement, no JSONB requirement, no concurrent-writer requirement. ADR 0001 commits us to a single Go binary on a Hetzner VPS; any datastore choice should preserve that shape where possible.

## Decision

Use **SQLite** as the sole datastore.

Concretely:

- **Driver:** `modernc.org/sqlite` — a pure-Go SQLite port, no cgo. This preserves the single-static-binary deploy from ADR 0001 (no libc sqlite on the target, no cross-compile dance).
- **File location on the VPS:** `/var/lib/entlib/db.sqlite`, owned by the service user. A `.sqlite-wal` and `.sqlite-shm` sit alongside it while the process runs.
- **Pragmas at connection open:**
  - `PRAGMA journal_mode = WAL;` — concurrent readers + one writer, crash-safe.
  - `PRAGMA foreign_keys = ON;` — not on by default; required for the cascade behavior the data model assumes.
  - `PRAGMA synchronous = NORMAL;` — the WAL-recommended setting; durable across crashes, faster than `FULL`.
  - `PRAGMA busy_timeout = 5000;` — waits 5 s before returning `SQLITE_BUSY`, which is plenty at our write rate.
- **Schema management:** [`pressly/goose`](https://github.com/pressly/goose) with `embed.FS`. Migrations live in `db/migrations/*.sql` in the repo and are compiled into the binary. The app runs `goose.Up()` on startup; no separate migration step in the deploy.
- **Backup:** nightly cron on the VPS runs `sqlite3 /var/lib/entlib/db.sqlite ".backup /tmp/entlib-$(date +%F).sqlite"` (safe under WAL), encrypts the result with `age`, and uploads it to a **Hetzner Storage Box** (decided 2026-04-18). Retention: 30 daily snapshots.

## Rationale

- **Matches the data.** Four tables, two writers, hundreds of rows lifetime. SQLite is over-provisioned for this by several orders of magnitude.
- **Preserves the single-binary property** from ADR 0001. The pure-Go driver means no cgo, no libc dependency, trivial cross-compile.
- **Backups are `cp` plus encryption.** Nothing else we'd pick is that simple.
- **No extra process** on the VPS. One systemd unit is still the whole app.
- **No vendor.** The data is in a file. Migration to anything else later is a standard export, not a vendor extraction.

## Alternatives considered

- **PostgreSQL, self-hosted on the same VPS.** A second process with its own config, backup procedure, and upgrade timeline. Real wins only appear when we need full-text search, concurrent writers, or JSONB. None of those apply.
- **PostgreSQL, managed (Neon / Supabase free tier).** Free at this scope, but introduces a vendor, a network hop per query, and a new failure mode. No benefit for the MVP.
- **Flat JSON files.** Rejected — no transactions, corruption risk under concurrent modification, no schema migration story.
- **Embedded KV (BoltDB / Badger).** The model is relational; a KV store would force hand-rolled indexing and joins.
- **`mattn/go-sqlite3` (cgo driver).** More battle-tested than the pure-Go port, but cgo breaks the single-binary promise and complicates cross-compile. We accept the tradeoff in favor of `modernc.org/sqlite`.

## Consequences

### Positive
- ~Zero operational overhead. One file, one backup script.
- The Go binary stays fully static; `GOOS=linux GOARCH=amd64 go build` from macOS still works with no toolchain pinning.
- Schema migrations ride along in the binary; no "I forgot to run migrations" class of deploy bug.
- Recovery from total VPS loss = provision a new CX22, restore the `.sqlite` file from the latest backup, done.

### Negative
- **Single writer.** If we ever need a second Go process to scale out, it cannot share this SQLite file meaningfully. The mitigation is to keep the app on one box; revisit if that stops being tenable.
- **`modernc.org/sqlite` is slower than the cgo driver** on write-heavy workloads (~1.5–2×). At our write rate, unmeasurable.
- **No managed PITR.** Point-in-time recovery beyond the last nightly snapshot is not available; we can lose up to 24 h of data in a catastrophic loss. Acceptable for this product.
- **Backup discipline is on us.** No "it's backed up automatically because it's managed" safety net.

### Follow-ups
- **Backup destination: Hetzner Storage Box** (decided 2026-04-18). Same-provider latency and simpler billing outweighed Backblaze B2's vendor-diversity argument at this scale. The accepted risk is a correlated-failure scenario (Hetzner-wide incident) — revisit if that becomes a real concern. Concrete configuration (endpoint, retention sweep) lives in `docs/runbooks/restore-from-backup.md` when that file exists.
- Provision `/var/lib/entlib/` and the service user during VPS setup.
- Write the restore runbook once ops actually exists (`docs/runbooks/restore-from-backup.md`).
- Define the cut-over criteria for Postgres. Suggested triggers: more than ~10 concurrent users routinely, the need for full-text search beyond SQLite's FTS5, or wanting to run two app servers.

## Sources

- [SQLite — Appropriate Uses For SQLite](https://www.sqlite.org/whentouse.html)
- [SQLite — Write-Ahead Logging](https://www.sqlite.org/wal.html)
- [`modernc.org/sqlite`](https://pkg.go.dev/modernc.org/sqlite)
- [`pressly/goose`](https://github.com/pressly/goose)
- [Hetzner Storage Box pricing](https://www.hetzner.com/storage/storage-box)
- [Backblaze B2 pricing](https://www.backblaze.com/cloud-storage/pricing)
