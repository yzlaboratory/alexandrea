# <Feature title in plain English>

<One short paragraph: who this is for, why it matters, and any links to
related ADRs. Delete this paragraph if the Feature block below is enough.>

```gherkin
Feature: <Same title, or a tighter restatement>
  As a <role>
  I want <capability>
  So that <outcome>

  Background:
    Given <shared precondition>
    And <another shared precondition>

  Scenario: <Happy path, named as an outcome>
    Given <precondition specific to this scenario>
    When <action>
    And <another action>
    Then <observable outcome>
    And <another observable outcome>

  Scenario: <Unhappy path>
    Given <precondition>
    When <action that fails>
    Then <how the system responds>

  Scenario Outline: <Parameterised scenario, optional>
    Given <precondition with <param>>
    When <action>
    Then <outcome involving <param>>

    Examples:
      | param   |
      | value-1 |
      | value-2 |
```

## Open questions

- <Things you haven't decided yet — kept out of the Gherkin block so the
  spec stays unambiguous about what *is* committed.>
