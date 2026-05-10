# Scheduled jobs: Spring `@Scheduled` for domain jobs, host cron for ops jobs

The system runs several recurring jobs. We split them by lifecycle
constraint into two homes, and pin which goes where.

## The split

**Spring `@Scheduled` (in-JVM, transactional, Spring-bean-using)** owns
**domain jobs** — work that reads or writes the application's data
through normal Spring services and benefits from the same JDBC pool,
the same `@Transactional` boundaries, and the same logging shape as
the request-serving code:

- **Unverified-account GC.** Daily sweep that deletes unverified
  accounts older than 7 days, per `create-account.md`. The sweep is
  required (not just lazy on signup collision) because the spec
  guarantees deleted accounts' verification links *"no longer work"*
  even when nobody triggers a fresh signup at the same address.
- **Expired-token cleanup.** Daily sweep over verification, reset,
  email-change, and any other expiry-bearing token tables for rows
  past their expiry. Token validation itself is already lazy-and-
  expiry-aware at the use site; this sweep is purely to keep the
  tables small.
- Any future domain-state housekeeping (e.g. pruning ancient
  email-rate-limit bucket rows past the rolling window) lands here
  by default.

**Host cron (on the EC2 instance, JVM-independent)** owns **ops jobs**
— work that must survive the application being down, or that
operates on artifacts outside the JVM's responsibility:

- **Daily SQLite backup to S3** — already specified in ADR 0014.
  Must be able to run during a deploy window when the container is
  restarting.
- Future: log rotation, host-level metrics emitters, anything that
  reaches outside SQLite or interacts with AWS at the OS level.

## Why a split rather than one home for everything

- **`@Scheduled` for backups is wrong.** The whole point of the
  backup is to be available after the JVM has died. A backup that
  only runs while the JVM is healthy is a backup that fails exactly
  when you need it most.
- **Host cron for domain GC is also wrong** in this codebase. Host
  cron would either (a) shell out to `sqlite3` directly, bypassing
  every Spring service that already encodes the deletion semantics
  (cascade rules per ADR 0016, transactional boundaries, structured
  logging), or (b) curl an authed admin endpoint, which adds an
  attack surface and an out-of-band auth credential for no benefit
  over `@Scheduled`. The first is an integrity risk, the second is
  needless surface area.
- **A consistent rule beats hand-picking each job.** Without the
  split rule above, three engineers will pick three places (a
  controller endpoint hit by curl, an ad-hoc `gc.sh`, an
  `@Scheduled` annotation) and the resulting topology is the union
  of every choice no one regrets enough to clean up. Pinning the
  split avoids the drift.

## Why no distributed-lock / leader-election

Per ADR 0014 we run **a single EC2 instance**. Both `@Scheduled`
and host cron run on exactly one host. No coordination is
required, and explicitly so — the moment a second app instance
appears, every `@Scheduled` job becomes incorrect (double-runs,
race conditions on the same SQLite file). That day is the day the
single-instance shape of ADR 0014 is itself superseded; this ADR
inherits the same "single-instance only" acceptance.

## Consequences

- **`@Scheduled` jobs run inside the same JVM that serves
  requests.** A long-running GC sweep that holds a write lock on
  SQLite competes with user requests; jobs must be small and
  bounded, or batched with `LIMIT`/sleep windows. WAL mode
  (per ADR 0014) keeps readers unblocked, but writers still
  serialise.
- **A scheduled job missed during downtime is *missed*, not queued.**
  If the container is down at the cron tick, the job simply does
  not run for that tick. Each job's next run picks up whatever
  state accumulated in the meantime — sweep jobs are by nature
  catch-up-friendly, so this is fine.
- **Host cron jobs and `@Scheduled` jobs share the same SQLite
  file** but never the same row class — backup reads `entlib.db`
  consistently via `sqlite3 .backup`; GC writes through Spring.
  No cross-process coordination is needed beyond what SQLite's
  WAL gives.
- **Job timing is configured per-job, not centrally.** Each
  `@Scheduled` job declares its own cron expression in code; the
  one host-cron job declares its expression in `crontab`. We keep
  the schedules narrow (daily, off-peak) and resist building a
  job-config abstraction until there is a real need.
