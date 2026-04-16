# ADR 0007: v0 Backend — Spring Boot + SQLite on a Single VPS

- **Status:** Accepted
- **Date:** 2026-04-15
- **Deciders:** Kira
- **Supersedes:** ADR 0001
- **Superseded by:** —
- **Related:** ADR 0004, ADR 0005, ADR 0002, `specs/00-overview.md`, `specs/09-data-model.md`

## Context

ADR 0001 specified a full AWS stack (Lambda, Aurora Serverless, Cognito, ElastiCache, CloudFront, SES, SNS, CDK, multi-account Organizations) on the assumption that the product would launch publicly with thousands of users and full GDPR/COPPA compliance. The repo review and the subsequent scope decision made clear that v0 is much smaller:

- **Two users** (Kira and her partner) on day one.
- **Web only**, mobile-responsive (ADR 0004). No native apps, no push notifications.
- **German + English** localization only.
- No paid commercial use during v0; TMDB free-tier license suffices (ADR 0002).

Running a multi-account AWS estate for two people is wasteful in money, ops time, and cognitive load. The user has chosen a deliberately small stack: **Spring Boot (Java) + SQLite + a cheap VPS.**

This ADR supersedes ADR 0001 for v0. The original AWS direction may be revisited in a future ADR if the product grows beyond the couple-only phase.

## Decision

**Run a single Spring Boot 3.x application backed by SQLite on a single small VPS, fronted by Caddy for TLS termination, with nightly off-site backups of the SQLite file.**

Concretely:

### Application

- **Language / runtime:** Java 21 LTS.
- **Framework:** Spring Boot 3.x (Web, Validation, Security, Data JDBC).
- **Persistence:** Spring Data JDBC over `sqlite-jdbc` with the community-maintained SQLite dialect. Hibernate is **not** used — Spring Data JDBC is a closer fit for SQLite's simpler model and avoids the Hibernate-on-SQLite quirks.
- **Migrations:** Flyway, with versioned SQL migrations under `src/main/resources/db/migration`.
- **Auth:** Spring Security with Sign in with Apple and Sign in with Google as the only sign-in methods (per `specs/01-accounts-and-sharing.md`). No local passwords, no 2FA in v0. Sessions via secure HTTP-only cookies.
- **HTTP API:** REST under `/api/**`, JSON request/response, ETag-aware on collection endpoints (per ADR 0005's polling model).
- **Static frontend serving:** the React build from ADR 0004 is bundled into `src/main/resources/static` and served by Spring Boot at the root. One artifact, one deploy.
- **i18n:** Spring `MessageSource` with `messages_de.properties` and `messages_en.properties` for backend strings (validation errors, transactional emails). The frontend handles its own i18n via react-i18next.
- **Build / packaging:** Maven, single fat JAR.
- **Testing:** JUnit 5 + Spring Boot Test + Testcontainers (SQLite in-process for unit tests, full app context for integration tests).

### Database (SQLite)

- Single database file at `/var/lib/entlib/entlib.sqlite` on the VPS.
- **WAL mode** enabled for safer concurrent reads/writes.
- **Foreign keys enabled** (`PRAGMA foreign_keys = ON`).
- Schema follows the data model in `specs/09-data-model.md`. SQLite is comfortably capable for the projected scale: tens of users, tens of thousands of titles in the local catalog cache, hundreds of thousands of `WatchEvent` rows lifetime.

### Deployment

- **VPS:** Hetzner Cloud **CX22** (or equivalent, ~€4–6/month) — 2 vCPU, 4 GB RAM, 40 GB SSD, in `nbg1` (Nuremberg) for low latency to German users.
- **OS:** Ubuntu 24.04 LTS.
- **Process manager:** systemd unit running `java -jar entlib.jar` with auto-restart.
- **Reverse proxy:** Caddy in front, terminating TLS via automatic Let's Encrypt, proxying to `localhost:8080`.
- **Domain:** TBD (placeholder `entlib.example.com`).
- **CI/CD:** GitHub Actions builds the fat JAR on push to `main`, SSHes into the VPS, replaces the JAR, restarts the systemd unit. Two-minute deploys.

### Backups

- Nightly `cron` job that:
  1. Runs `sqlite3 entlib.sqlite ".backup /tmp/backup-$(date +%F).sqlite"` (online backup, safe under WAL).
  2. Encrypts with `age` using a public key.
  3. Uploads to **Backblaze B2** (~$6/TB/month; expected monthly cost <$0.10).
  4. Retains 30 daily, 12 monthly snapshots.
- Restore procedure documented in `docs/runbooks/restore-from-backup.md` (to be created when ops actually exists).

### Email (transactional only)

- **Resend** or **Mailgun** at the free / cheapest tier (under 100 emails/month is comfortably free on either).
- Used for: shared-space invite emails. Account flows run through Apple/Google SSO so there are no password resets or verification mails to send.
- No marketing email, no digests in v0.

### Observability

- **Spring Boot Actuator** for `/health` and `/metrics` (only accessible from localhost; not exposed via Caddy).
- **journalctl** for logs; rotated by systemd.
- That's it for v0. No APM, no Prometheus, no dashboards. If something breaks, ssh in and read logs.

### Costs (v0 monthly target)

| Item | Cost |
| --- | --- |
| Hetzner CX22 VPS | ~€5/month |
| Domain (`.app` or `.de`) | ~€1/month amortized |
| Backblaze B2 storage | <€0.10/month |
| Resend / Mailgun (free tier) | €0/month |
| TMDB API (non-commercial, attribution required) | €0/month |
| **Total** | **~€6/month** |

## Rationale

- **Matches scope.** Two users, web-only, occasional collaboration. SQLite is famously over-provisioned for this load. Spring Boot + SQLite is a stack that runs comfortably on a $5 VPS.
- **No vendor lock-in.** Everything runs on a single Linux box. The data is in a single file. Migration to anything else (Postgres, managed PaaS, AWS later) is a `pg_dump`-equivalent away.
- **Cheap to operate.** ~€6/month total. One server to babysit. One log to read.
- **Java is what's familiar.** Spring Boot 3 + Java 21 is a productive, mature stack with strong type safety and the best ecosystem for testing, validation, and HTTP plumbing in the JVM world.
- **SQLite + WAL handles writes from a couple effortlessly.** Estimated peak write rate: <1 write per second. SQLite's documented write ceiling is well above this.

## Alternatives considered

- **Postgres on the same VPS.** Slightly more operational weight (separate process, configuration, backup). For two users, no benefit. Revisit when adding the third concurrent writer or when full-text search needs outpace SQLite's FTS5.
- **Managed PaaS (Fly.io, Railway, Render).** Slightly easier ops but ~2–4× the cost and proprietary deploy models. Rejected for v0.
- **Stay on AWS (per ADR 0001).** ~$30–80/month minimum even at near-zero traffic, plus ongoing IAM/CDK overhead. Massively oversized.
- **Self-host on a Raspberry Pi at home.** Cheapest possible, but requires home network reliability, dynamic DNS, and physical maintenance. Rejected for the small monthly cost of cloud.
- **Kotlin + Ktor instead of Java + Spring.** Lighter framework, but the user named Spring Boot. Java 21 is fine; Kotlin can come later.
- **Node + Fastify instead of Spring Boot.** Equivalent in capability; rejected because Java was named.

## Consequences

### Positive

- ~€6/month all-in.
- Single artifact, single host, single database file. Easy mental model.
- Backups are a 30-line shell script.
- No managed-service learning curve. No IAM. No VPC.

### Negative

- **Single point of failure.** One VPS dies → app is down until backup is restored to a new VPS. Acceptable for a couple-only tool; mitigation is the off-site backup.
- **No horizontal scaling.** SQLite is single-writer; Spring Boot can't be load-balanced behind multiple instances against the same SQLite file. Acceptable until v1.
- **Manual TLS, manual systemd, manual cron.** Owning the box means owning the box. The user has chosen this trade.
- **Loss of managed services from ADR 0001:** no Cognito (we own auth), no SES (we use Resend/Mailgun), no CloudFront (Caddy serves static assets directly — fine for two users), no SNS (no push, web-only).
- **JVM cold-start memory footprint** (~250–400 MB) on a 4 GB VPS leaves plenty of headroom but is heavier than a Go or Node equivalent. Acceptable.

### Follow-ups

- Procure the VPS and domain.
- Stand up the Spring Boot skeleton and Flyway baseline matching `specs/09-data-model.md`.
- Write the GitHub Actions deploy workflow.
- Write the backup and restore runbook.
- Define the cut-over criteria for v1: when do we migrate to Postgres? Suggested triggers: more than 5 active users, or more than 100k `WatchEvent` rows, or felt latency on common queries.

## Sources

- [Spring Boot reference documentation](https://docs.spring.io/spring-boot/index.html)
- [SQLite — Appropriate Uses For SQLite](https://www.sqlite.org/whentouse.html)
- [SQLite — Write-Ahead Logging](https://www.sqlite.org/wal.html)
- [Hetzner Cloud pricing](https://www.hetzner.com/cloud)
- [Caddy — Automatic HTTPS](https://caddyserver.com/docs/automatic-https)
- [Backblaze B2 pricing](https://www.backblaze.com/cloud-storage/pricing)
