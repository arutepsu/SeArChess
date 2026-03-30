# ARCHITECTURE.md

## Purpose

This document describes the architectural intent of the project.

It exists to keep the system coherent as implementation grows across:
- chess rules and game state
- text UI
- GUI
- persistence and notation
- future HTTP/API and web integration

This document is primarily for:
- the human maintainer
- the architect/reviewer
- AI implementers working inside defined boundaries

It should answer:
- what the main parts of the system are
- who owns which responsibility
- which dependencies are allowed
- what must remain stable as the project evolves

---

## Architectural goals

The project should optimize for:

1. **Correctness of chess behavior**
2. **Clear separation of responsibilities**
3. **High testability**
4. **Functional-style reasoning**
5. **Safe extensibility for future adapters**
6. **Low accidental complexity**

The architecture should make it easy to:
- evolve chess rules safely
- add new adapters without reshaping the core
- test domain logic independently from UI and infrastructure
- keep rendering, persistence, and transport concerns outside the core model

---

## High-level structure

The system is organized around a layered architecture with explicit boundaries.

```text
User / External World
        |
        v
+---------------------------+
| Adapters                  |
| - TUI                     |
| - GUI                     |
| - Persistence             |
| - HTTP / Web (future)     |
+---------------------------+
        |
        v
+---------------------------+
| Application / Use Cases   |
| - game orchestration      |
| - command handling        |
| - flow coordination       |
+---------------------------+
        |
        v
+---------------------------+
| Domain                    |
| - board                   |
| - pieces                  |
| - moves                   |
| - rules                   |
| - validation              |
| - game status             |
+---------------------------+

Dependency direction must always point inward:

adapters depend on application/domain
application depends on domain
domain depends on nothing outside itself

The domain is the center of the system.

Layer responsibilities
1. Domain

The domain owns the chess model and chess rules.

Examples of responsibilities:

board representation
positions, pieces, colors, moves
move legality
rule validation
check/checkmate/stalemate logic
castling, en passant, promotion rules
domain errors
game status evaluation

The domain must:

be independent of UI
be independent of persistence format
be independent of HTTP/transport concerns
remain framework-free
remain highly testable

The domain should express:

core invariants
rule logic
precise domain language

The domain must not contain:

rendering logic
animation logic
file system logic
JSON/XML/HTTP-specific concerns
direct console interaction
GUI toolkit dependencies
2. Application / Use Cases

The application layer orchestrates domain behavior.

It owns:

use-case flow
command execution
coordination between adapters and domain
preparing results for outside consumers
application-level state transitions if needed

Examples of responsibilities:

executing a move request
applying user intent to domain actions
coordinating promotion choice flow
mapping domain outcomes into adapter-facing results
sequencing domain operations
enforcing use-case level policies

The application layer may depend on:

domain
abstractions for persistence or external services

The application layer must not own:

raw GUI rendering
chess rules that belong in domain
persistence format details
framework-driven transport logic

A useful rule:

the application layer decides when things happen
the domain decides what is valid
adapters decide how things are shown or stored
3. Adapters

Adapters are the boundary to the outside world.

Examples:

TUI
GUI
persistence
notation import/export
future HTTP/API endpoints
future web UI

Adapters are responsible for:

input translation
output presentation
representation mapping
interaction with external protocols or frameworks

Adapters must not define chess truth.

TUI

Owns:

command input/output
user prompts
textual representation of the game

Must not own:

move legality logic
game rules
authoritative game state semantics beyond presentation/use-case interaction
GUI

Owns:

visual presentation
scene composition
animation playback
sprite loading
render-model mapping
visual state transitions derived from application/domain state

Must not own:

chess rules
authoritative move validation
game-state truth not already established elsewhere
Persistence / Notation

Owns:

saving/loading
FEN/PGN or other representations
serialization/deserialization
filesystem or external storage concerns

Must not own:

the domain model itself
chess semantics beyond faithful representation translation
HTTP / Web (future)

Owns:

request/response handling
DTOs
API contracts
transport-specific validation
authentication/session concerns if added later

Must not own:

chess rules
board legality logic
core game orchestration semantics already defined in application/domain
Dependency rules
Allowed
adapters -> application
adapters -> domain
application -> domain
Not allowed
domain -> adapters
domain -> frameworks
application -> GUI toolkit types
application -> transport protocol details
one adapter depending directly on another adapter unless explicitly justified

Examples:

GUI may consume domain/application state
TUI may call application use cases
persistence adapter may map domain state to FEN/JSON/XML
domain must never import GUI, TUI, HTTP, or file concerns
Core architectural principles
1. Domain-first design

Chess concepts come first.

Technical structures should reflect the domain, not distort it.

Prefer:

Move, Board, Piece, Position, GameStatus
over vague technical names like:
DataManager, Processor, Handler, Util

When a concept is central to chess behavior, it should likely live in domain language.

2. Pure logic separated from effects

Where practical:

decision logic should be pure
effects should be pushed outward

Examples:

move validation should be pure
game status evaluation should be pure
render-model derivation should be pure
file IO should remain outside pure rule logic
GUI event handling may be effectful, but should consume already-derived state

This improves:

testability
clarity
debuggability
3. Derived state over duplicated state

Prefer deriving secondary views from authoritative state rather than storing multiple copies.

Examples:

visual render state should be derived from domain/application state
board display should not become a second game model
notation output should be derived from game state, not tracked separately unless truly required

Duplication is allowed only when it has a clear, bounded reason.

4. Stable seams

The architecture should expose stable boundaries where change is expected:

adapters may change frequently
persistence formats may expand
GUI rendering details may evolve
domain invariants should change more slowly and carefully

Do not create extension points everywhere.
Create them where variation is real and likely.

5. Local complexity over global complexity

Prefer a small local workaround over a premature shared abstraction.

A new abstraction is justified when:

responsibility is clearly repeated
the owner is obvious
the concept improves reasoning across multiple places

Do not generalize too early.

Suggested conceptual module map

The exact package names may evolve, but the conceptual split should remain similar.

chess
├── domain
│   ├── model
│   ├── rules
│   ├── validation
│   ├── status
│   └── error
│
├── application
│   ├── service
│   ├── usecase
│   ├── command
│   └── result
│
├── adapter
│   ├── tui
│   ├── gui
│   │   ├── scene
│   │   ├── rendering
│   │   ├── animation
│   │   └── assets
│   ├── persistence
│   ├── notation
│   └── http
│
└── bootstrap
    └── wiring / composition root

This is a conceptual map, not a requirement for exact folder names.

GUI-specific architectural guidance

The GUI is an adapter, not part of the core chess model.

GUI should own
scene/layout composition
piece rendering
sprite metadata and loading
animation timing and playback
visual overlays
mapping from domain/application state to render models
GUI should not own
legal move truth
board-rule enforcement
game status truth
persistence semantics
Preferred GUI flow
Domain/Application State
        |
        v
Pure Presentation Mapping
        |
        v
Render Model
        |
        v
ScalaFX / GUI Rendering

This keeps:

rendering toolkit code thin
animation policy explicit
visual logic testable independently from toolkit behavior
Animation principle

Animation is presentation behavior.
It should not modify chess truth.

The GUI may animate:

movement
attack sequences
hit reactions
death/capture visuals
highlights/selection

But these visuals must always be derived from already-established game events or state.

Persistence and notation guidance

Representations such as:

FEN
PGN
JSON
XML
save-game snapshots

are external or adapter-facing representations.

They should map to/from the core domain explicitly.

Important rule:
representation convenience must not deform the core model.

For example:

do not make the domain awkward just to match a save format
do not leak serializer-specific concerns into domain types
do not let API DTO shape dictate domain structure
Future web/API guidance

A future web/API layer should be treated as another adapter.

Recommended conceptual flow:

HTTP Request
   -> route/controller
   -> DTO parsing
   -> application use case
   -> domain
   -> application result
   -> DTO/response mapping
   -> HTTP response

The API layer should stay thin.

It should not:

replicate domain rules
become a second application layer
embed chess logic in controllers
Testing strategy by layer
Domain tests

Focus on:

chess rules
invariants
edge cases
move legality
check/checkmate/stalemate behavior
promotion, en passant, castling

These should be the strongest and most numerous tests.

Application tests

Focus on:

use-case orchestration
correct sequencing
error/result mapping
integration of domain operations at use-case level
Adapter tests

Focus on:

translation correctness
render-model behavior
serialization/deserialization behavior
UI-specific presentation logic where practical

Do not push rule correctness testing into adapters if the rule belongs in domain.

Architectural decision guidelines

When adding a new concept, ask:

Is this chess truth or only representation?
Does this belong in domain, application, or adapter?
Is this a real abstraction or just indirection?
Can this be a pure mapping instead of a stateful coordinator?
Am I preserving dependency direction?
Am I solving a current problem or inventing for the future?

If those answers are unclear, keep the change small and avoid introducing a shared abstraction yet.

Non-goals of this architecture

This project is not trying to:

maximize abstraction count
introduce patterns for their own sake
make every subsystem infinitely generic
mix transport, rendering, and domain into a single convenience layer
optimize prematurely at the cost of clarity

The preferred style is:

explicit
testable
bounded
understandable
Signs of architectural drift

The following are warning signs:

GUI starts deciding legal chess behavior
persistence format shapes the domain model
domain imports adapter/framework types
the same rule appears in multiple layers
new vague classes appear (Manager, Helper, Util) without strong ownership
state is duplicated across layers without a clear source of truth
every local problem becomes a new abstraction

When drift appears, fix responsibility first, not just code style.

Summary

The system should remain centered on a pure, testable chess domain.

Domain owns chess truth
Application owns orchestration
Adapters own presentation, translation, and external interaction

Everything else should support that structure.

The architecture is successful when:

rules are easy to reason about
adapters remain replaceable
implementation can evolve without collapsing boundaries
AI contributors can implement safely without inventing structure