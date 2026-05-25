## Agent skills

This repo uses Kira's engineering skills. Specs, PRDs, and implementation issues are kept **out of the source tree** — they live in the tracker below. Only `CONTEXT.md` and `docs/adr/` are committed.

### Tracker — GitHub Issues

Specs, PRDs, and issues live in GitHub Issues on `yzlaboratory/entertainment-library`.

- **Feature ticket** — a GitHub issue. Its **body** holds, for one feature, three sections in order: `## PRD`, `## Spec` (strict Gherkin), and `## Out of scope`. Created by `/create-alignment-and-refine-docs` (or passed to it if one already exists).
- **Issue** — a GitHub sub-issue of a feature ticket. One tracer-bullet vertical slice, produced by `/to-issues`.
- Use the `gh` CLI for all tracker reads and writes. Link a child to its feature ticket with `gh issue edit <feature-ticket> --add-sub-issue <child>`.
- Nothing is closed automatically. Once a feature's PR merges to `main`, its feature ticket and issues are simply stale — ignore them.

### Branch naming

A feature branch names the ticket it implements: `<issue-number>-<slug>` (e.g. `42-checkout-flow`). All commits for that feature — including every sub-issue's work — land on this single branch. Sub-issues do not get their own named branches; subagents implement them in worktrees that branch off the feature branch and merge back into it. Skills derive the current ticket from the feature branch name; worktrees inherit it.

### Domain docs (in-repo)

Before exploring the codebase, read `CONTEXT.md` (domain glossary) and the ADRs under `docs/adr/` that touch the area you're working in. These are the only planning docs committed to the repo. If they don't exist yet, proceed silently — they're created lazily by `/create-alignment-and-refine-docs`. Use the glossary's vocabulary in all output; flag any output that contradicts an ADR.
