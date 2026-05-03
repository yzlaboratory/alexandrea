# Specs

Behavioral specs for the entertainment library, written as **strict Gherkin
inside markdown**. Each `.md` file describes one feature and its scenarios.

## Conventions

- One feature per file. Filename is kebab-case and matches the `Feature:` name
  (e.g. `add-movie.md` → `Feature: Add a movie to my library`).
- Gherkin lives inside a fenced ` ```gherkin ` block. Free-form prose
  (motivation, open questions, links to ADRs) goes outside the block.
- Use the standard keywords: `Feature`, `Background`, `Scenario`,
  `Scenario Outline` + `Examples`, `Given`, `When`, `Then`, `And`, `But`.
- Steps are declarative — describe behavior, not UI mechanics. Prefer
  "I save the film" over "I tap the green button in the top-right".
- Each `Scenario` should be independently understandable; lift shared
  preconditions into `Background`.
- Edge cases get their own `Scenario`, not a bullet list.
- Open questions and design tensions go in a trailing `## Open questions`
  section in prose — do not encode them as Gherkin comments.

## Pairing with ADRs

Specs capture **what the user experiences**. ADRs (`docs/adr/`) capture
**why we built it that way**. When a scenario depends on a non-obvious
decision, link to the ADR from the prose section above the Gherkin block.

## Template

Copy `_template.md` when starting a new spec.
