## SeArChess — Scala 3 Chess Engine

[![Coverage Status](https://coveralls.io/repos/github/arutepsu/SeArChess/badge.svg?branch=main)](https://coveralls.io/github/arutepsu/SeArChess?branch=main)

### Usage

```bash
sbt run          # start the text UI
sbt compile      # compile
sbt test         # run tests
sbt report       # tests + coverage report
sbt ci           # tests + coverage + Coveralls upload
```

---

## Architecture

---

## Domain Core Model

The domain core defines the fundamental chess entities: `Board`, `Piece`, `Color`, `PieceType`, `Position`, `Move`, `MoveResult`, and `GameStatus`. These types are pure data — they carry no behavior, have no dependencies on application workflows or UI concerns, and form the stable foundation that all other layers build upon.

![Domain Core Model](docs/diagrams/DomainCoreModel.png)

---

## Position State

Castling rights and en passant eligibility are explicitly modeled as first-class types (`CastlingRights`, `EnPassantState`) in a dedicated `positionstate` sub-package. This makes transient game state visible and immutable rather than derived implicitly, ensuring that both validation and application logic receive exactly the state they need.

![Position State](docs/diagrams/PositionState.png)

---

## Move Processing Pipeline

Move execution is split into four sequential responsibilities: validation (is the move legal?), application (produce the new board), evaluation (what is the resulting game status?), and state update (revise castling rights and en passant eligibility). Each stage is a stateless object with a single well-defined input and output, making the pipeline easy to test and extend independently.

![Move Processing Pipeline](docs/diagrams/MoveProcessingPipeline.png)

---

## Application Layer

`GameStateCommandService` orchestrates game workflows by advancing an immutable `GameState` snapshot — the authoritative record of the current board, active color, castling rights, and en passant state — in response to `ChessCommand` inputs. It delegates all rule logic to the domain pipeline and assembles results; no domain decisions are made here.

![Application Layer](docs/diagrams/ApplicationLayer.png)

---

## Promotion Workflow

When a pawn reaches the back rank, `MoveApplier` returns a `MoveResult.PromotionRequired` rather than `Applied`. `GameStateCommandService` records this as a `PendingPromotion` in `GameState` and suspends normal move processing until the player supplies a piece type. This two-step interaction keeps promotion as a first-class domain event rather than an edge case patched into the move loop.

![Promotion Workflow](docs/diagrams/PromotionWorkflow.png)

---

## Dependency Diagram

Dependencies flow strictly inward: the `adapter` layer depends on `application`, which depends on `domain`. The domain does not depend on the application or adapter layers. This constraint ensures that UI changes, persistence strategies, or transport mechanisms can evolve without touching domain or application logic.

![Dependency Diagram](docs/diagrams/DependencyDiagram.png)

---

## Test Strategy

Tests mirror the production package structure, with each test class covering exactly one production object or service. This alignment means components can be tested independently, with domain rule logic verified through minimal board setups and `GameStateCommandService` verified end-to-end through command sequences. The 100% statement and branch coverage gate is enforced on every build via `sbt report`.

![Test Strategy](docs/diagrams/TestStrategy.png)

---

## Future Extension

The architecture supports incremental extension without restructuring the domain. Export formats (JSON, PGN, FEN), HTTP transports (http4s, fs2), persistence backends (MongoDB, PostgreSQL), and web UIs all plug in at the adapter layer. The same `GameStateCommandService` interface can evolve toward microservices or streaming architectures as requirements grow.

![Future Extension](docs/diagrams/FutureExtension.png)
