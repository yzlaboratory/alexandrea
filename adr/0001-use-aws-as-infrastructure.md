# ADR 0001: Use AWS as the Infrastructure Provider

- **Status:** Superseded
- **Date:** 2026-04-15
- **Deciders:** Kira
- **Supersedes:** —
- **Superseded by:** ADR 0007 (for v0 / couple-only scope)

> **Status note (2026-04-15):** This ADR was written under the assumption of a public-product launch. The v0 scope was subsequently reduced to a couple-only, web-only application, for which the AWS estate described below is wildly oversized. **ADR 0007 supersedes this decision for v0.** The contents below are preserved as historical context and as a reference for if/when the product grows beyond the couple-only phase.

## Context

The entertainment-library product (see `specs/00-overview.md`) ships as a web app and native mobile apps backed by a single API and a shared metadata/rating store. The backend needs to:

- Serve a small but global user base with low-latency reads on lists, homepages, and detail pages.
- Store user data (watch history, ratings, lists) with strong durability, backup, and point-in-time recovery.
- Cache posters and backdrops at the edge.
- Run nightly metadata refresh jobs against upstream providers (TMDB, Rotten Tomatoes, JustWatch).
- Handle authentication (email + password, Sign in with Apple, Sign in with Google, TOTP 2FA) per `specs/01-accounts-and-sharing.md`.
- Deliver push notifications to iOS and Android and send transactional email.
- Comply with GDPR (EU), CCPA (California), and COPPA (US) — including data export, deletion with a 30-day grace period, and regional data residency options.
- Stay cheap enough to run for a two-person couple's early-access phase, but scale linearly as friends and early adopters join.

A cloud provider is required; self-hosting is a non-starter for a two-person team. The realistic options are AWS, Google Cloud, Azure, and Cloudflare (edge-first, with R2/D1/Workers).

## Decision

**Adopt AWS as the sole infrastructure provider for compute, storage, data, networking, and auth-adjacent services.**

Concretely, the expected starting stack is:

- **Compute:** AWS Lambda for API handlers and background jobs; AWS Fargate for anything that outgrows Lambda's runtime limits.
- **API gateway:** Amazon API Gateway (HTTP API) in front of Lambda.
- **Primary data store:** Amazon Aurora PostgreSQL (Serverless v2) for user, library, list, and rating data.
- **Cache:** Amazon ElastiCache (Redis) for hot reads (homepage, discovery rails).
- **Object storage:** Amazon S3 for exports, imports, and cached poster assets.
- **CDN:** Amazon CloudFront in front of S3 and the API.
- **Auth:** Amazon Cognito for user pools (email + password, Apple, Google, TOTP).
- **Email:** Amazon SES for transactional email (verification, password reset, suspicious-activity alerts).
- **Push:** Amazon SNS + platform-specific mobile pushes (APNs, FCM).
- **Secrets:** AWS Secrets Manager for upstream provider credentials (TMDB, RT, JustWatch).
- **Observability:** CloudWatch Logs, CloudWatch Metrics, and AWS X-Ray as a baseline; a third-party APM may be layered on later if needed.
- **IaC:** AWS CDK (TypeScript) for everything environment-defining.
- **Accounts:** A multi-account setup from day one (`dev`, `staging`, `prod`) under AWS Organizations.
- **Regions:** Primary `us-east-1`; EU residency in `eu-west-1` activated when the first EU user signs up.

## Rationale

- **Breadth of managed services.** AWS covers every capability the spec requires (auth, email, push, SQL, cache, CDN, edge, secrets) under one bill and one IAM model. This minimizes vendor sprawl for a two-person team.
- **Durability and compliance posture.** AWS offers mature compliance attestations (SOC 2, ISO 27001, GDPR DPA) that make the privacy commitments in `specs/00-overview.md` (export, deletion, residency) defensible without custom work.
- **Serverless cost profile.** Lambda + Aurora Serverless + CloudFront scale to near-zero at low traffic, which suits the early-access phase. We pay for what we use.
- **Ecosystem and hiring.** AWS is the most widely-known cloud; any future contractor or hire is overwhelmingly likely to know it.
- **Mobile integration.** Cognito, SNS, and the AWS Amplify SDKs have first-class iOS and Android support, which matters given the three-platform strategy.

## Alternatives considered

- **Google Cloud (Firebase + Cloud Run).** Strong mobile story via Firebase, but Aurora-class managed SQL is weaker; vendor lock is similar to AWS while the ecosystem is smaller.
- **Azure.** Plausible on paper, but overweight on enterprise-oriented tooling and underweight on serverless ergonomics for a small product.
- **Cloudflare (Workers + R2 + D1 + Queues).** Best edge story and lowest cost floor, but D1 is not yet mature enough to be the primary store for rating and history data that demands point-in-time recovery. Revisit for CDN/edge layering later.
- **Self-hosted on a single VPS (Hetzner/DigitalOcean).** Cheapest, but the operational load — backups, TLS, runtime upgrades, security patches, compliance — is untenable for a two-person team.

## Consequences

### Positive

- Single vendor, single bill, single IAM model.
- Rapid setup: email verification, push notifications, and managed SQL are each one CDK construct away.
- Compliance evidence (backups, encryption at rest, access logs) is available out of the box.

### Negative

- **Lock-in.** Cognito, SES, and Aurora are non-trivial to migrate off. This is accepted in exchange for speed.
- **Cost cliffs.** Some AWS services (NAT gateways, CloudWatch ingestion, cross-region data transfer) have surprising bills if misconfigured. Mitigation: cost alarms from day one and a monthly review.
- **Learning curve.** IAM and VPC networking are the usual stumbling blocks. Mitigation: CDK-first so patterns are reused, not reinvented.

### Follow-ups

- Create a second ADR for the specific data model and database choice (Aurora PostgreSQL vs. DynamoDB) once the schema firms up.
- Create an ADR for the CI/CD pipeline (GitHub Actions → CDK deploy into `dev` / `staging` / `prod`).
- Document cost guardrails (budget alarms, reserved capacity thresholds) before the first external user.
