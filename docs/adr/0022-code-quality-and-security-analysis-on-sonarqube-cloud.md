# Code quality and security analysis run on SonarQube Cloud (Free tier today, Team plan when we outgrow it)

The codebase has two analyzed surfaces: the **React 19 + TypeScript** frontend
bundled by Vite, and the **Java 25 + Spring Boot 4** backend (both per
ADR 0014). Per-language static analysis already runs locally and in CI —
ESLint, Knip, and react-doctor on the frontend; the JVM compiler and Spring's
build-time checks on the backend. **SonarQube Cloud** sits above these as the
cross-language **code-quality and security gate**: it decorates pull requests
inline, runs a deeper analyzer pass than the local linters, and tracks quality
trends over time.

## Why SonarQube Cloud (SaaS) over alternatives

Three alternatives were considered and rejected:

- **SonarQube Server, Community Edition (self-hosted)** — free, but no
  PR decoration, no branch analysis, no security hotspots, and no merge-
  blocking quality gates wired to PR status. Operationally it adds a JVM
  process and a Postgres dependency that ADR 0014 explicitly does not have
  — SQLite is the only datastore. Rejected on both feature gap and ops
  surface.
- **CodeQL via GitHub Advanced Security** — strong security analysis, but
  free only for public repos and security-only in scope. It does not cover
  code smells, complexity, duplication, or coverage trends as a unified
  view. Useful as a complement; insufficient as the primary gate.
- **Just the local toolchain** (ESLint + Knip + react-doctor + the JVM
  compiler) — fast and good at what they do, but they don't track trend over
  time, don't measure cognitive/cyclomatic complexity across files, don't
  detect cross-file duplication, and don't map findings to OWASP/CWE for
  security hotspots. Useful as the developer-time signal; insufficient as
  the audit layer.

SonarQube Cloud was chosen because it:

- **Decorates PRs inline** on GitHub with annotations on the actual changed
  lines.
- **Has a "new code is clean" quality gate** that blocks merges on
  regressions in the *changed* lines, so legacy debt does not gate forward
  progress.
- **Covers both Java and TypeScript** with first-class analyzers — no
  separate per-language SaaS to wire up.
- **Has a usable free tier** (50k LOC across all private projects, or
  unlimited LOC for public repos) that we expect to live in for v1.
- **Has a transparent upgrade path** — Team plan at $32/month covers up to
  100k LOC with no architecture change.

## What SonarQube covers vs the local toolchain

The roles are deliberately non-overlapping where possible:

- **ESLint** — TypeScript/React correctness, hooks rules, jsx-a11y. Runs
  locally (lint-staged on pre-commit, per ADR 0023 if/when it lands) and
  in CI on every PR.
- **Knip** — unused files, exports, dependencies, and types. Runs locally
  and in CI.
- **react-doctor** — deterministic React anti-pattern audit across state &
  effects, performance, architecture, security, and accessibility. Runs
  locally and as a GitHub Action that annotates PRs.
- **madge** — import-cycle detection. Runs in CI.
- **JVM compiler + Spring's build-time checks** — Java correctness,
  deprecation surface, configuration validation. Runs in Gradle.
- **SonarQube Cloud** — cross-cutting code smells, cognitive and cyclomatic
  complexity, cross-file duplication, security hotspots, OWASP/CWE
  mappings, security-hotspot review queue, trend tracking, and the
  branch/PR quality gate. Runs in CI on every PR and on `main` after
  merge.

Where overlap is unavoidable — basic lint rules duplicated between ESLint and
Sonar's TypeScript analyzer, basic Java rules duplicated between the
compiler and Sonar's Java analyzer — the local tool runs first and fails
fast; Sonar's pass is the audit, not the primary gate.

## Tier we start on and the upgrade trigger

- **We start on the Free tier.** It allows up to **50,000 LOC** combined
  across all private projects in the org, or **unlimited LOC** if the
  repository is public. Frontend + backend at v1 scope are comfortably
  under the cap.
- **Upgrade trigger** — combined analyzed LOC crosses **40,000 (80% of
  the cap)** for two consecutive analyses. At that point we move to the
  **Team plan ($32/month)** which extends the cap to 100k LOC. Above 100k
  we revisit.
- **Public-repo alternative** — if `yzlaboratory/entertainment-library`
  is made public, the LOC cap disappears and the only reasons to upgrade
  are Team-tier features (advanced security reports, OWASP/MISRA bundles,
  AI-driven code fixes). That decision is deferred — it depends on whether
  we want the codebase public, not on Sonar's pricing.

## Quality gate and CI integration

- **CI runs SonarQube analysis on every PR** as part of the GitHub Actions
  workflow. The Sonar action posts the quality-gate result back to the PR;
  a failed gate **blocks merge**.
- **The quality gate starts as the "Sonar way" default** — zero new bugs,
  zero new vulnerabilities, ≥80% coverage on new code, ≤3% duplication on
  new code, all new security hotspots reviewed. We tune once we have a
  baseline of real findings.
- **The gate applies to new code only.** Legacy findings are reported but
  do not block PRs — matching how ESLint and Knip already behave.
- **Coverage data feeds Sonar from Vitest** (`--coverage --reporter=lcov`)
  and from **JaCoCo** on the backend. Sonar does not run tests itself; it
  consumes the LCOV/JaCoCo report files emitted by CI.
- **The Sonar token lives in GitHub Encrypted Secrets** (`SONAR_TOKEN`),
  never in the repo. Per the global credential convention (`~/.credentials`
  on dev machines, GitHub Secrets in CI), no secret ships in source.

## Consequences

- **A third-party CI dependency is now in the merge path.** If Sonar Cloud
  is down the quality gate fails open (PRs can still merge); if Sonar Cloud
  is slow the PR cycle slows. Accepted.
- **The Sonar token is a long-lived secret** that must be rotated through
  the same path as other GitHub Secrets. No mechanical rotation today.
- **LOC growth is now a metered cost.** Crossing 50k LOC requires a
  billing decision; the upgrade trigger above pre-commits the answer so
  the decision is not made under deadline pressure.
- **Local linters remain the primary developer-time signal.** Sonar is
  the audit layer that runs at CI time. Failing fast at the local tool is
  still the goal, and a PR that passes ESLint/Knip/react-doctor locally
  should rarely surprise the developer at Sonar's gate.
- **react-doctor and Sonar overlap on React anti-patterns.** This is
  accepted — react-doctor is React-specific, deterministic, and produces
  actionable inline PR annotations; Sonar's view is broader, historical,
  and quality-gate-bound. Both run; their findings are read independently.
- **The accessibility floor remains ADR 0010 + MUI defaults.** Sonar's
  TypeScript analyzer has light a11y coverage; react-doctor has React-
  specific a11y checks. Neither replaces a manual axe DevTools sweep.
  There is no automated WCAG 2.1 AA gate in CI — that is an accepted
  v1 gap, audited rather than enforced.
