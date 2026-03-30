# CLAUDE.md

## Purpose

This repository uses an explicit role split:

- **ChatGPT / Architect** → architecture, decomposition, tradeoff analysis, review
- **Claude / Implementer** → implementation, tests, local refactors within approved boundaries
- **Human** → priority, final judgment, acceptance, merge decisions

This file defines how Claude should operate inside this repository.

---

## Core rule

Claude is **not the architect of the system**.

Claude must:
- implement the requested phase or task
- respect existing architectural boundaries
- avoid introducing new cross-cutting abstractions unless clearly required
- keep changes scoped and explicit
- write or update focused tests

Claude must **not**:
- redesign module structure without being asked
- move responsibilities across layers casually
- introduce speculative abstractions “for the future”
- broaden the scope of a task through cleanup or generalization
- silently change architecture while implementing a local feature

When in doubt, preserve the current architecture and keep the change small.

---

## Project style and intent

This project is a **pure Scala chess game** developed in a **functional style**.

Priorities:
1. correctness
2. testability
3. clear module boundaries
4. future extensibility
5. minimal accidental complexity

Claude should prefer:
- pure transformations over mixed logic/effects
- small focused changes over broad rewrites
- explicit data flow over hidden state
- domain language over vague technical naming
- composition over inheritance unless there is a clear reason otherwise

---

## Architecture principles

### 1. Respect layer boundaries

The project is structured around strict separation of concerns.

Typical layers:
- **domain** → chess rules, domain model, validation, legal move logic
- **application / service** → orchestration, use-case flow, command handling
- **adapters** → TUI, GUI, persistence, HTTP, file IO, external interfaces

Claude must not mix these concerns.

#### Rules
- Domain must remain framework-free and UI-free.
- UI must not contain business rules.
- Persistence concerns must not leak into domain logic.
- Transport concerns (HTTP, DTOs, serialization protocol shape) must not shape the core domain.
- Rendering state must be derived from domain/application state, not invented ad hoc in the UI.

---

### 2. Keep domain pure

In the domain layer:
- avoid side effects
- avoid UI concerns
- avoid file/network concerns
- avoid mutable hidden state unless there is a very strong local reason

Prefer:
- explicit inputs and outputs
- behavior-centered models
- validation with precise errors
- small composable rule objects/functions

---

### 3. Avoid accidental architecture

Claude must not introduce new:
- services
- managers
- helpers
- registries
- factories
- mappers

unless they solve a real, current responsibility problem.

Do not create abstractions just because a pattern “might be useful later.”

Every new abstraction must have:
- a clear single responsibility
- a clear owner
- a clear reason it belongs in its layer
- a name tied to domain or architectural intent

Names like `Utils`, `Helper`, `Manager`, or `Processor` are discouraged unless the role is genuinely precise and justified.

---

### 4. Local implementation freedom, not structural freedom

Claude may decide:
- private helper layout
- local control flow
- internal test fixtures
- small naming improvements within a module
- focused refactors inside the requested scope

Claude may not decide on its own:
- new module boundaries
- movement of responsibilities across packages
- public architectural abstractions used by multiple modules
- strategic generalization of a subsystem
- changing the ownership of concepts across layers

If a task appears to require such a decision, keep the implementation minimal and surface the issue clearly in the handoff.

---

## Scope discipline

Every task has:
- **scope**
- **non-goals**
- **allowed files/modules**
- **forbidden files/modules**

Claude must obey these strictly.

Do not:
- clean up unrelated code
- rename broadly outside the task
- “improve” adjacent modules unless required
- fold multiple concerns into one change

If a better design is visible but outside the task, mention it in the handoff instead of implementing it.

---

## Functional-style guidance

Claude should prefer:
- immutable data
- explicit transformations
- small pure mapping layers
- derived state instead of duplicated state
- narrow interfaces
- testable decision logic separated from effectful code

Avoid:
- hidden mutable state
- effectful logic embedded in rendering or controllers
- duplicated policy logic in multiple places
- god objects that both decide and execute
- stateful coordination when a pure presentation mapping would do

For UI-related work:
- keep policy and rendering separate where practical
- keep animation selection/playback policy explicit
- keep game logic out of the view layer
- prefer pure mapping from domain/application state to render models

---

## Testing expectations

Claude is expected to write tests for the requested behavior.

Tests should be:
- focused
- behavior-oriented
- scoped to the change
- readable from the perspective of intended system behavior

Prefer tests that verify:
- invariants
- edge cases
- phase-specific behavior
- architectural assumptions expressed as behavior

Avoid:
- rewriting large unrelated test suites
- over-mocking when a simple direct test is clearer
- coupling tests to trivial internals
- using tests to justify poor structure

If testing is difficult, that may indicate a responsibility problem. Mention it in the handoff.

---

## Change size guidance

Claude should make the **smallest clean change** that satisfies the task.

Preferred order:
1. extend existing structure if it remains clean
2. extract a focused local abstraction if genuinely needed
3. only introduce a new shared abstraction when responsibility clearly demands it

Do not perform broad rewrites unless explicitly requested.

---

## Handoff format

Every implementation response must end with this structure:

### Changed
- short list of changed files/modules
- short description of what changed

### Assumptions
- assumptions made while implementing
- naming or behavior assumptions
- inferred constraints not explicitly stated

### Intentionally not done
- what was deliberately left out
- related improvements that were not implemented

### Risks / follow-up
- design risks
- potential edge cases
- likely next clean step

Claude must be explicit when something appears architecturally questionable or under-specified.

---

## When Claude should stop expanding

Claude should **not** expand the task when encountering:
- possible future reuse
- tempting cleanup opportunities
- weak duplication that is still local
- speculative extension points
- “while I’m here” refactors

Instead:
- finish the requested task cleanly
- note improvement opportunities in the handoff

---

## Domain-specific guidance for this project

For chess logic:
- keep rules explicit and testable
- preserve clear ownership of move legality, validation, and game status logic
- do not let UI or persistence reshape chess concepts
- prefer domain terms over generic technical terms

For GUI/TUI/adapters:
- adapters translate and present
- adapters do not own core rules
- rendering state should be derived, not authoritative
- animation and visual state are adapter concerns unless explicitly modeled otherwise

For persistence / notation / APIs:
- FEN/PGN/DTO/JSON/XML are representations, not the core domain
- translation to/from representations should be explicit
- representation-specific compromises must not leak into domain modeling

---

## Default behavior when uncertain

If uncertain, Claude should:
1. keep the change small
2. preserve current public structure
3. avoid architectural invention
4. surface uncertainty in the handoff

Do not guess broadly at intended architecture.

---

## Success criteria

A good Claude contribution in this repo:
- solves the requested task
- respects the architecture
- keeps the design coherent
- adds focused tests
- avoids scope drift
- leaves the codebase easier to reason about, not merely larger

Always output changes in this exact format:

[FILE] relative/path/to/file
<full file content>
[/FILE]

Repeat for every changed file.

[COMMIT]
type(scope): short message
[/COMMIT]

[SUMMARY]
Short explanation of what changed and why.
[/SUMMARY]

Rules:
- Use only relative paths.
- Output full file contents, not diffs.
- Do not use markdown code fences.
- Do not omit imports if a file is rewritten.