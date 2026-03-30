# DECISIONS.md

## Purpose

This file records important architectural decisions for the project.

It exists to:
- prevent re-deciding the same questions repeatedly
- keep humans and AI contributors aligned
- make tradeoffs explicit
- document why a decision was taken, not just what was chosen

This is not a changelog.

It should only contain decisions that affect:
- architecture
- boundaries
- ownership of responsibilities
- major modeling choices
- long-term extensibility

---

## Status meanings

Use these statuses for each decision:

- **Accepted** → current decision in force
- **Provisional** → chosen for now, may be revised after a later phase
- **Superseded** → replaced by a later decision
- **Rejected** → considered and intentionally not chosen

---

## Decision template

Each decision should use this structure:

```text
Decision ID:
Title:
Status:
Date:

Context:
...

Decision:
...

Alternatives considered:
- ...
- ...

Why this was chosen:
...

Consequences:
- positive:
- negative:
- follow-up:
Accepted decisions
Decision ID: D-001
Title: Use explicit architect/implementer role split
Status: Accepted
Date: 2026-03-29
Context

The project is developed with AI assistance. Different tasks require different strengths:

architecture and tradeoff reasoning
implementation and test writing
final human judgment

Without a clear split, architectural decisions risk being made ad hoc during implementation, causing drift.

Decision

The project uses this role split:

Human → priorities, acceptance, merge decisions
Architect AI → architecture, review, decomposition, tradeoff analysis
Implementer AI → implementation, tests, local refactors within approved scope
Alternatives considered
One AI does everything end-to-end
Human handles architecture implicitly without documentation
Implementer AI decides architecture during coding
Why this was chosen

This gives speed without giving up structure. It keeps architectural reasoning explicit and prevents implementation from silently reshaping the system.

Consequences
positive:
clearer decision ownership
less architectural drift
easier review and prompting
negative:
requires discipline and written instructions
can feel slower at the start of a feature
follow-up:
maintain CLAUDE.md, ARCHITECTURE.md, and this file as living references
Decision ID: D-002
Title: Keep the chess domain pure and framework-free
Status: Accepted
Date: 2026-03-29
Context

The project will grow from simple chess rules and TUI toward GUI, persistence, APIs, and possibly web integration. If framework and adapter concerns leak into the core, future evolution becomes harder.

Decision

The domain layer remains:

framework-free
UI-free
persistence-format-free
transport-free

The domain owns chess truth only.

Alternatives considered
Let UI or persistence concerns shape the domain directly
Use one merged layer for faster implementation
Put domain and application logic into controllers or scenes
Why this was chosen

Pure domain logic is easier to test, reason about, and extend. Chess rules are the most stable and important part of the system and should not be distorted by adapter needs.

Consequences
positive:
high testability
clear boundaries
easier future adapters
negative:
requires explicit mapping at boundaries
some duplication of translation logic may exist across adapters
follow-up:
review new abstractions carefully to ensure they belong outside the domain unless they represent chess truth
Decision ID: D-003
Title: Use layered architecture with inward dependency direction
Status: Accepted
Date: 2026-03-29
Context

The system needs to support multiple interaction modes and external representations over time.

Decision

The conceptual structure is:

domain
application
adapters
bootstrap/composition root

Dependencies must point inward:

adapters -> application/domain
application -> domain
domain -> nothing external
Alternatives considered
Feature-oriented structure without clear dependency rules
Adapter-heavy design with logic spread across UI/persistence layers
Flat package layout with implicit conventions only
Why this was chosen

The layered approach makes responsibility ownership explicit and reduces coupling between chess truth and external concerns.

Consequences
positive:
clearer reasoning
safer extensibility
better review discipline
negative:
requires explicit translation between layers
some tasks need more up-front design
follow-up:
ensure new packages and abstractions are consistent with this dependency direction
Decision ID: D-004
Title: Application layer owns orchestration, not chess truth
Status: Accepted
Date: 2026-03-29
Context

The system needs a place to coordinate user intent, use-case flow, and domain operations without pushing orchestration into UI or transport code.

Decision

The application layer owns:

use-case sequencing
command handling
orchestration
adapter-facing result preparation

The domain remains responsible for:

legality
invariants
chess rules
game status truth
Alternatives considered
Put orchestration into UI adapters
Let domain grow orchestration concerns
Collapse application and adapters together
Why this was chosen

This preserves a clean boundary between “what is valid” and “how the system performs a use case.”

Consequences
positive:
thinner adapters
cleaner domain
easier future HTTP/API integration
negative:
requires care not to create vague application “manager” classes
follow-up:
prefer focused use-case/application services over broad coordinator objects
Decision ID: D-005
Title: Adapters translate and present, but do not define chess truth
Status: Accepted
Date: 2026-03-29
Context

TUI, GUI, persistence, and future HTTP layers all need to consume and expose the system differently.

Decision

Adapters are responsible for:

input/output translation
presentation
external representation mapping
framework/protocol interaction

Adapters must not define:

move legality
board truth
game status truth
core chess invariants
Alternatives considered
Let each adapter enforce its own version of rules
Duplicate rule checks in adapters for convenience
Make the adapter layer authoritative for user interactions
Why this was chosen

A single source of truth keeps chess behavior coherent and prevents conflicting logic between interfaces.

Consequences
positive:
fewer inconsistent rules
cleaner adapter responsibility
easier testing
negative:
adapters may still need lightweight local validation for UX, but must not become authoritative
follow-up:
distinguish clearly between UX validation and chess truth
Decision ID: D-006
Title: Prefer pure mapping layers for presentation-related transformations
Status: Accepted
Date: 2026-03-29
Context

GUI and other adapters often need to transform domain/application state into render or presentation models. Mixing this logic directly into framework code makes it hard to test and reason about.

Decision

Where practical, presentation policy should be expressed as pure mapping:

domain/application state
-> presentation mapping
-> render model
-> framework rendering
Alternatives considered
Put transformation logic directly into UI scenes/controllers
Keep all presentation logic stateful and toolkit-driven
Derive render state ad hoc at each call site
Why this was chosen

Pure mapping keeps rendering code thinner and makes policy testable without depending on the GUI toolkit.

Consequences
positive:
better testability
clearer separation of policy vs rendering
easier refactoring
negative:
introduces extra model types in some places
follow-up:
use this pattern especially for GUI animation and render-model derivation
Decision ID: D-007
Title: GUI is an adapter and owns presentation-only concerns
Status: Accepted
Date: 2026-03-29
Context

The GUI will include piece rendering, animation, overlays, sprite loading, and visual state transitions. There is a risk of letting gameplay semantics drift into the view layer.

Decision

The GUI owns:

scene composition
rendering
animation playback
sprite loading and metadata
visual overlays
presentation models derived from domain/application state

The GUI does not own:

rule truth
move legality
game status truth
authoritative board semantics
Alternatives considered
Put some gameplay truth into the GUI for convenience
Let animation state alter game truth
Couple GUI decisions directly to domain internals without mapping
Why this was chosen

The GUI should remain replaceable and not become a second rules engine.

Consequences
positive:
easier GUI evolution
less coupling
better architectural consistency
negative:
requires explicit event/state mapping for visual behavior
follow-up:
keep animation semantics presentation-only unless a future requirement explicitly promotes them to a higher-level model
Decision ID: D-008
Title: Treat animation as presentation behavior, not game truth
Status: Accepted
Date: 2026-03-29
Context

The GUI will animate movement, attacks, hits, and capture/death states. There is a temptation to let animation timing or visual phases influence chess logic.

Decision

Animation is derived from already-established domain/application state or events.
Animation does not modify chess truth.

Alternatives considered
Let animation phases drive gameplay state
Delay rule truth until visual completion
Mix animation coordination and domain state mutation together
Why this was chosen

Chess truth should remain deterministic and independent from rendering timing.

Consequences
positive:
clear separation of truth vs visual effect
easier testing
fewer timing-related bugs in core logic
negative:
GUI may need extra coordination models for smooth playback
follow-up:
keep visual timelines in adapters and map from authoritative game events/state
Decision ID: D-009
Title: Representations such as FEN, PGN, JSON, XML are external forms, not the core domain
Status: Accepted
Date: 2026-03-29
Context

The project will likely support serialization, save/load, notation, and APIs.

Decision

Formats such as:

FEN
PGN
JSON
XML
save snapshots
DTOs

are treated as external representations.
They must map explicitly to/from the core domain.

Alternatives considered
Shape the domain primarily around a serialization format
Expose persistence or DTO structures as the main model
Collapse domain and representation models into one
Why this was chosen

Representation concerns change more frequently and should not dictate the structure of chess truth.

Consequences
positive:
cleaner domain
easier support for multiple formats
clearer adapter boundaries
negative:
requires mapping layers
some repetitive translation code may exist
follow-up:
review import/export code to ensure it does not leak representation concerns inward
Decision ID: D-010
Title: Prefer smallest clean change over speculative generalization
Status: Accepted
Date: 2026-03-29
Context

AI implementers often overgeneralize by adding abstractions for imagined future needs. This creates accidental complexity.

Decision

The default implementation strategy is:

extend the existing structure if it stays clean
extract a local abstraction only when clearly needed
introduce shared abstractions only when responsibility truly demands them
Alternatives considered
Generalize proactively for future reuse
Add infrastructure around every new feature
Solve local problems with large architectural patterns
Why this was chosen

The project benefits more from clarity and control than from speculative flexibility.

Consequences
positive:
lower complexity
fewer unused abstractions
easier reviews
negative:
some local duplication may exist temporarily
follow-up:
refactor only when duplication or responsibility problems become real and repeated
Decision ID: D-011
Title: Favor explicit domain language over vague technical naming
Status: Accepted
Date: 2026-03-29
Context

Names strongly shape architecture. Vague classes like Manager, Helper, and Util often hide poor ownership.

Decision

Prefer names tied to:

chess concepts
architectural role
specific responsibility

Discourage vague umbrella names unless the responsibility is genuinely precise and justified.

Alternatives considered
Use generic technical names for flexibility
Allow broad helper/manager naming as a convenience
Why this was chosen

Precise names improve reasoning, reveal ownership, and discourage accidental god objects.

Consequences
positive:
more readable design
easier reviews
better module boundaries
negative:
requires more careful naming effort
follow-up:
challenge vague names during review, especially in application and adapter layers
Provisional decisions
Decision ID: D-012
Title: Store some GUI asset metadata explicitly rather than infer everything dynamically
Status: Provisional
Date: 2026-03-29
Context

The GUI sprite system will need metadata such as:

frame counts
frame sizes
display sizes
anchors
state sequencing rules

There is a choice between pure convention-based inference, external config files, or explicit in-code metadata tables/repositories.

Decision

For now, allow explicit metadata to exist in a dedicated GUI asset-side structure rather than trying to infer everything automatically from file names or image dimensions.

Alternatives considered
infer all metadata dynamically from files
store all metadata in external config files immediately
hardcode metadata ad hoc at render call sites
Why this was chosen

Early GUI work benefits from clarity and predictable control. Full dynamic inference is fragile, and ad hoc hardcoding spreads policy across the codebase.

Consequences
positive:
clear ownership
easy testability
explicit control during early GUI evolution
negative:
metadata maintenance overhead
may later need conversion to config if the asset set becomes large
follow-up:
revisit after the sprite system stabilizes and the real volume of assets is known
Decision ID: D-013
Title: Keep future web support as an adapter, not a redesign of the core
Status: Provisional
Date: 2026-03-29
Context

The project may later add HTTP endpoints and a web UI. There is a risk of prematurely shaping today’s architecture around hypothetical service boundaries or microservices.

Decision

For now, assume web/API support will be added as another adapter over the same application/domain core, not through a redesign of the core architecture.

Alternatives considered
design as distributed services from the start
make transport/API concerns first-class in the current core
postpone all web considerations completely
Why this was chosen

The current project scope benefits more from a strong modular monolith with clean boundaries than from early distribution complexity.

Consequences
positive:
simpler current architecture
preserves future options
avoids premature service boundaries
negative:
some future extraction work may be needed if a distributed architecture later becomes necessary
follow-up:
revisit when concrete web/API requirements exist
Rejected decisions
Decision ID: D-014
Title: Let each adapter perform its own version of game rule enforcement
Status: Rejected
Date: 2026-03-29
Context

It can be tempting to let TUI, GUI, or HTTP layers each perform their own move rule checks for convenience.

Decision

Rejected. Adapters may support UX-oriented checks or guidance, but authoritative chess rule enforcement belongs in the core.

Alternatives considered
adapter-specific rule enforcement
duplicated rule checks in several layers
Why this was rejected

This causes inconsistency, duplication, and long-term drift between interfaces.

Consequences
positive:
single source of truth
negative:
adapters must call inward rather than decide locally
follow-up:
clearly separate convenience checks from authoritative validation
Decision ID: D-015
Title: Over-generalize early for future unknown features
Status: Rejected
Date: 2026-03-29
Context

AI tools often suggest highly abstract designs “for future flexibility.”

Decision

Rejected. The project will not introduce broad abstraction layers for hypothetical needs without concrete current pressure.

Alternatives considered
early plugin-style generalization everywhere
pre-emptive abstraction for all likely future growth
Why this was rejected

This usually increases indirection and weakens clarity before the real variation points are known.

Consequences
positive:
simpler design
lower mental overhead
negative:
some future refactoring may be necessary once real variation appears
follow-up:
prefer incremental extraction when duplication or responsibility pressure is visible