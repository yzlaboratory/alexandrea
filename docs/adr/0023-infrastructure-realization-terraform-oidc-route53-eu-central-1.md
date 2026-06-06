# Infrastructure realization for v1: Terraform IaC, OIDC-based CI, Route 53 DNS, eu-central-1 single EC2

This ADR realizes the abstract stack of ADR 0014 into a **concrete v1
deployment topology**. Where 0014 said "single CloudFront + single EC2 +
S3 + SQLite in AWS," this ADR pins the **account, region, domain, DNS
host, IaC tool and state, credential flows, secrets store, environments,
branch posture, dev workflow, and backup retention** for the build-out.
ADR 0014 stands; this fills in the specifics it left abstract.

## Domain and DNS

- **Registrar — Porkbun.** Domain `alexandrea.app` registered there.
  ~$15/year renewal, no markup. The `.app` TLD is HTTPS-only by registry
  policy, which matches the CloudFront-fronted TLS posture of ADR 0014.
- **DNS — AWS Route 53.** A hosted zone in the same account as the rest
  of the infra. Porkbun's NS records delegate the entire `alexandrea.app`
  zone to Route 53's four nameservers. All records — CloudFront alias at
  apex, ACM cert validation CNAMEs, SES DKIM and DMARC records — live in
  Route 53 and are Terraform-managed.

Considered and rejected:

- **DNS at Porkbun (free).** Workable — Porkbun supports ALIAS-style
  apex records and can host the ACM/SES validation records. Rejected
  because keeping DNS in the AWS account makes the entire infra
  Terraform-managed from one provider and `terraform plan` covers DNS
  drift too. The $0.50/month for the hosted zone is the price of that
  uniformity.
- **Cloudflare DNS (free).** Adds a third vendor (Porkbun + AWS +
  Cloudflare) with no benefit — ADR 0014 already commits to CloudFront,
  so Cloudflare's CDN/WAF would be unused.

## Account and region

- **AWS account — existing personal account.** No fresh-account hygiene
  required at this scale. Project-scoped resources go under a prefix
  (`alexandrea-prod-*` / `/alexandrea/prod/...`) so blast-radius and
  clean-up are unambiguous.
- **Compute region — eu-central-1 (Frankfurt).** Lowest latency to the
  operator, and SES is generally-available in production there.
  Spring Boot, SQLite-on-disk, the EC2 instance, the S3 backups bucket,
  the SSM Parameter Store hierarchy, and the Terraform state bucket all
  live in eu-central-1.
- **CloudFront's ACM cert lives in us-east-1.** ACM-for-CloudFront has
  to, regardless of where the origin is. This is the one cross-region
  piece — one ACM resource declared with `provider = aws.us_east_1` in
  Terraform. Nothing else is in us-east-1.

## Infrastructure as code

- **Terraform** is the IaC tool. State lives in an **S3 backend with
  native locking** (Terraform 1.10+ removed the DynamoDB lock-table
  requirement). The state bucket is encrypted (SSE-S3), versioned, and
  named `alexandrea-tfstate`.
- **Bootstrap is manual.** The state bucket itself must exist before any
  `terraform apply` against the S3 backend works. It is created once by
  hand via the console or CLI, then never touched again. The provider
  configuration in the repo points at this bucket from the start. The
  GitHub OIDC provider and the CI IAM role (see below) are also part of
  the manual bootstrap, since CI cannot create the identity that lets
  it run Terraform.
- The repo organizes Terraform under a top-level `infra/` directory,
  peer to `frontend/` and the future `backend/`.

## CI ↔ AWS authentication

- **GitHub Actions assumes an IAM role via OIDC.** GitHub's
  `token.actions.githubusercontent.com` is registered as a trusted
  identity provider in the AWS account. One project-scoped role:
  `alexandrea-prod-github-actions`. The role's trust policy is **scoped
  to this specific repo** (`yzlaboratory/entertainment-library`) by
  `sub` claim and rejects token requests from any other source.
- **No static IAM keys in GitHub Secrets.** The only long-lived secrets
  in GitHub are third-party tokens that AWS does not issue: the
  SonarQube Cloud token (per ADR 0022) and any others that arrive later
  for explicitly third-party services. AWS-side secrets (Grafana Cloud
  Loki write token, app session keys) live in SSM Parameter Store and
  are read by the EC2 instance role, not by CI.

Considered and rejected: **long-lived IAM access keys in GitHub Secrets.**
Standard pattern in 2026 is OIDC; the static-key path has no remaining
advantage at this account scale and a permanent leak surface.

## Local AWS authentication for the operator

- `~/.aws/credentials` already configured for this personal account.
  Used as-is. Long-lived keys are accepted at this scale; rotate
  manually every 6–12 months.
- Local AWS auth is **only** for bootstrap (creating the OIDC provider
  and the role itself) and ad-hoc reads. Day-to-day `terraform apply`
  runs from CI under the OIDC role. The operator runs `terraform plan`
  locally to review changes before opening the PR.

## Runtime secrets

- **SSM Parameter Store** holds all runtime secrets, each as a
  `SecureString` under the path prefix `/alexandrea/prod/`. The default
  SSM KMS key (`aws/ssm`) is used; no customer-managed key at v1.
- The EC2 instance role has `ssm:GetParameter` and
  `ssm:GetParametersByPath` on `/alexandrea/prod/*` and nothing
  broader. Spring Boot loads parameters at boot via the Spring Cloud
  AWS Parameter Store starter; there is no fallback to host env vars.
- Concrete secrets stored at v1: the Spring Session signing key, the
  Grafana Cloud Loki tenant write token (consumed by the Alloy
  companion container per ADR 0014's amended observability bullet),
  the CSRF token signing key, and any admin-endpoint password. Each
  parameter has a clear name (`/alexandrea/prod/session-signing-key`,
  `/alexandrea/prod/loki-write-token`, etc.).
- **SES credentials are not in Parameter Store.** The EC2 instance role
  has IAM permission for `ses:SendEmail` on the verified identity, and
  the AWS SDK uses the instance-profile credentials. There is no SES
  secret to store.

Considered and rejected: **AWS Secrets Manager.** Its real value is
native Lambda-based rotation for services with rotating passwords
(RDS/Aurora), which we do not have — SQLite has no auth. At ~$0.40 per
secret per month, the v1 set of secrets would cost ~$2/month for
behaviour we would not use. If a future need for cross-region replicated
secrets or coordinated DB-password rotation emerges, Secrets Manager can
be added *alongside* Parameter Store without removing it.

## SES sender identity

- The verified SES identity is the **subdomain `mail.alexandrea.app`**,
  not the root domain. The `From:` address on all transactional mail is
  `noreply@mail.alexandrea.app` (verification, password reset, and
  email-change confirmation per ADR 0021). The `Reply-To` is the same
  address.
- DKIM and DMARC live on the subdomain only. The root domain's
  reputation is unaffected by transactional bounces or spam complaints.
  If a future use case ever sends marketing or human-replied mail from
  the root domain, the identities are already separated.
- **Sandbox exit is requested early.** The production-access request is
  filed as soon as the DKIM CNAMEs validate, in parallel with backend
  implementation, so the 24–48 hour AWS review window is not the
  bottleneck at launch.

## Environments

- **Single environment: production.** No staging, no per-PR ephemeral
  environments at v1. Blast radius is the operator's own data; rollback
  is `git revert` + redeploy. The cost of a real staging env (a second
  EC2 plus a second CloudFront behavior) is not justified for a
  single-user application.
- Ephemeral preview environments per PR are deferred. If review-time
  confidence ever materially hurts, the option is real; it is not built
  at v1.

## Branch protection on `main`

- Branch protection is enabled with: linear history, all CI status
  checks required (lint, typecheck, Vitest, Knip, madge, react-doctor,
  Sonar quality gate), one approval required (which the operator
  self-approves for solo work), **admin bypass allowed**.
- Admin bypass exists for the ADR-edit and infra-config-tweak class of
  commit where a PR would be ceremony for ceremony's sake. Anything
  that touches `frontend/`, `backend/`, or `infra/` goes through a PR.

## Backup retention specifics

This subsection makes concrete the backup posture ADR 0014 sketched.

- **Daily SQLite dump** via `sqlite3 entlib.db ".backup
  /tmp/entlib-<timestamp>.db"`, shipped to
  `s3://alexandrea-prod-backups/YYYY/MM/DD/entlib-<timestamp>.db`.
  Cron on the EC2 host — ADR 0017's split puts this in host cron, not
  Spring `@Scheduled`.
- **Bucket name — `alexandrea-prod-backups`.** Env-qualified for
  forward compatibility with a future staging env.
- **Encryption — SSE-S3.** The default S3-managed key. Adequate for
  personal-tracker data; a customer-managed KMS key would add ~$1/month
  for no real risk reduction at this scale.
- **Lifecycle.**
  - 0–30 days: Standard storage class (fast restore for recent
    oops-recovery).
  - 30 days–3 years: Glacier Flexible Retrieval (cheap, hours-to-
    restore).
  - After 3 years: object expiration.
- **Versioning is on.** A re-uploaded same-key object preserves the
  prior version (collisions should be rare since the timestamp path
  makes them unlikely, but versioning is the floor).

## Local backend dev workflow

- **Docker Compose** is the local development entry point for the
  backend. `docker compose up` builds the same image CI ships and
  mounts a SQLite volume. This tests the prod-shape container at every
  iteration, catches Dockerfile mistakes immediately, and surfaces any
  missing instance-profile-fallback code paths that would otherwise
  only show up in CI.
- **Each `/implement-issues` worktree gets its own SQLite path**
  (parameterized by worktree directory) so subagents running in parallel
  do not contend on the SQLite file lock.
- `./gradlew bootRun` against a local SQLite file remains a supported
  escape hatch for breakpoint debugging, but the canonical workflow is
  compose.

## Consequences

- **One AWS account holds everything project-related.** The naming
  prefix (`alexandrea-prod-*` / `/alexandrea/prod/*`) is the marker for
  "delete to nuke v1." Nothing project-related lives outside this
  prefix.
- **Terraform is the source of truth.** Anything visible in the AWS
  console that is not in `infra/` is a drift bug. State lives in
  `s3://alexandrea-tfstate/` with native locking.
- **CI cannot do anything to AWS that the OIDC role's IAM policy does
  not allow.** Adding a new resource type to the deploy (e.g. Lambda,
  ECS) requires a Terraform PR that expands the role's policy first.
  This is intentional friction.
- **All runtime secrets live in SSM under `/alexandrea/prod/`.** A new
  secret added anywhere else (env var on host, file in `/etc`,
  hardcoded constant) is a bug.
- **SES has a domain identity at `mail.alexandrea.app`, not
  `alexandrea.app`.** Any future code path that tries to send from the
  root domain will fail SES verification and bounce.
- **Backups expire after 3 years.** A recovery scenario older than that
  is unsupported. RPO and RTO from ADR 0014 still hold (≤24 hours and
  minutes respectively).
- **No staging environment exists.** A bug that escapes CI lands on the
  operator immediately. Rollback is `git revert` + redeploy, with the
  cross-version skew window bounded by CloudFront invalidation latency
  (≤1 minute, per ADR 0014).
- **Branch protection requires PRs for code paths.** Direct main
  commits are still possible via admin bypass for ADR edits and
  infra-config tweaks where a PR would be ceremony.
- **The us-east-1 ACM cert is the only resource outside eu-central-1.**
  Any infra change that adds a second us-east-1 resource needs an
  explicit reason; otherwise prefer eu-central-1.
