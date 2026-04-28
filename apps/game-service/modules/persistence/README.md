# adapter-persistence Blueprint

## Purpose

`adapter-persistence` is the infrastructure adapter for state storage.

It should:
- implement repository ports required by the application layer
- persist and load backend state
- hide storage details behind stable interfaces
- remain replaceable as storage technology changes

Its role is data access, not business logic.

## It is not

It must not become:
- a business module
- an application service layer
- a transport adapter
- a place for workflow orchestration
- a source of domain rules

The application defines what must be stored.  
`adapter-persistence` defines how it is stored.

## Core rule

Persistence sits behind application repository ports.

Correct model:

Client  
-> REST adapter  
-> application service  
-> repository port  
-> `adapter-persistence`

Not:

Client  
-> REST adapter  
-> database directly

And not:

`adapter-persistence`  
-> business decisions  
-> application orchestration

## What it should own

- concrete repository implementations
- database or storage access
- serialization or mapping to persistence models
- resource usage needed for storage access
- loading and saving state through repository boundaries

## What it should not own

- chess rules
- use-case orchestration
- HTTP or WebSocket behavior
- event distribution
- runtime profile selection
- direct client-facing API shapes

## Main architectural rules

1. Application depends on repository ports; persistence implements them.
2. Transport adapters must not access persistence directly.
3. Persistence models must not become the public API or domain model by accident.
4. Storage technology choice belongs to `game-service`, not to routes or services.
5. `adapter-persistence` must stay replaceable for in-memory, Postgres, Mongo, or other backends later.

## Why this matters

This keeps the architecture clean:

- application = use cases and repository ports
- `adapter-persistence` = concrete storage implementation
- transport adapters = client access only
- `game-service` = selects which persistence adapter is active

That separation is what makes testing, swapping storage backends, and later microservice evolution manageable.

# SessionRepository Slice

## Purpose

`SessionRepository` is the first completed persistence slice.

It persists session metadata only:
- session identity
- associated game identity
- session mode
- White and Black controllers
- lifecycle state
- creation and update timestamps

It does not persist chess board state, move history, notation, archive records, outbox events, or game results. Those belong to later persistence slices or other ports.

The assignment calls this the DAO pattern. Inside the codebase, the application-facing abstraction is called a Repository because it represents a domain/application persistence boundary rather than a low-level database table API.

## Port Contract

The application/core layer owns the port:

```scala
trait SessionRepository:
  def save(session: GameSession): Either[RepositoryError, Unit]
  def load(id: SessionId): Either[RepositoryError, GameSession]
  def loadByGameId(id: GameId): Either[RepositoryError, GameSession]
  def listActive(): Either[RepositoryError, List[GameSession]]
```

All methods use:

```scala
Either[RepositoryError, A]
```

The port exposes no in-memory, Slick, SQL, Mongo, BSON, or driver-specific types.

## Shared Behavioral Guarantees

The shared contract tests enforce these rules for every implementation:
- `save` persists the exact `GameSession` value at the repository boundary.
- `save` is upsert-style for the same `SessionId`.
- `load(SessionId)` returns the saved session.
- missing `SessionId` returns `Left(RepositoryError.NotFound(...))`.
- `loadByGameId(GameId)` returns the session that owns that game id.
- missing `GameId` returns `Left(RepositoryError.NotFound(...))`.
- one session owns one `GameId`.
- a different `SessionId` cannot claim an already-owned `GameId`; this returns `Left(RepositoryError.Conflict(...))`.
- `listActive()` returns `Right(List.empty)` when no active sessions exist.
- `listActive()` includes `Created`, `Active`, and `AwaitingPromotion`.
- `listActive()` excludes `Finished` and `Cancelled`.

## Implementation Summary

### InMemory

Implementation:

```text
chess.adapter.repository.InMemorySessionRepository
```

Storage shape:

```text
Map[SessionId, GameSession]
Map[GameId, SessionId]
```

The first map provides primary lookup by `SessionId`.
The second map provides lookup by `GameId` and conflict detection.

The in-memory adapter stores `GameSession` directly because there is no external serialization boundary. It is useful for local runs and fast tests, but it is not durable across process restarts.

### PostgreSQL/Slick

Implementation package:

```text
chess.adapter.repository.postgres
```

Relational shape:

```text
sessions
  session_id                   UUID primary key
  game_id                      UUID unique
  mode                         text/varchar
  white_controller_kind         text/varchar
  white_controller_engine_id    nullable text/varchar
  black_controller_kind         text/varchar
  black_controller_engine_id    nullable text/varchar
  lifecycle                    text/varchar
  created_at                   timestamp
  updated_at                   timestamp
```

Slick table, row, schema, and mapper code stay inside the Postgres adapter package.

`game_id` has a unique constraint. The repository also checks current ownership before upsert so normal duplicate ownership returns `RepositoryError.Conflict`. The unique constraint is still needed as a database-level safety net.

### MongoDB

Implementation package:

```text
chess.adapter.repository.mongo
```

Document shape:

```json
{
  "_id": "session uuid",
  "sessionId": "session uuid",
  "gameId": "game uuid",
  "mode": "HumanVsHuman",
  "whiteController": {
    "kind": "AI",
    "engineId": "stockfish-15"
  },
  "blackController": {
    "kind": "HumanLocal"
  },
  "lifecycle": "Active",
  "createdAt": "2024-01-01T00:00:00Z",
  "updatedAt": "2024-01-01T00:01:00Z"
}
```

Mongo document and mapper code stay inside the Mongo adapter package.

The collection has unique indexes on `sessionId` and `gameId`. The repository checks current game ownership before replacement, and duplicate-key errors are mapped to `RepositoryError.Conflict`.

## Cross-Implementation Rules

All implementations must:
- preserve the application-level `GameSession` value.
- return `Either[RepositoryError, A]`.
- return `NotFound` for missing single-record lookups.
- return `Conflict` for duplicate `GameId` ownership.
- return an empty list, not `NotFound`, when no active sessions exist.
- exclude terminal lifecycles from `listActive()`.
- keep database-specific models and errors inside the adapter boundary.
- avoid storing `GameState` or any chess board data in session persistence.

## Allowed Implementation Differences

These may differ by adapter and should be documented when relevant:
- ordering of `listActive()` results.
- durability across process restarts.
- concurrency guarantees.
- exact conflict/storage error message text.
- physical schema/index names.
- timestamp storage representation, as long as the repository preserves the `Instant` values used by the contract tests.
- test setup strategy.

## How to Run the Tests

Run the full persistence module tests:

```powershell
sbt "adapterPersistence/test"
```

Run only the in-memory session contract:

```powershell
sbt "adapterPersistence/testOnly chess.adapter.repository.InMemorySessionRepositorySpec"
```

Run the PostgreSQL/Slick session contract:

```powershell
$env:SEARCHESS_POSTGRES_URL="jdbc:postgresql://localhost:5432/searchess_test"
$env:SEARCHESS_POSTGRES_USER="postgres"
$env:SEARCHESS_POSTGRES_PASSWORD="postgres"

sbt "adapterPersistence/testOnly chess.adapter.repository.postgres.PostgresSessionRepositorySpec"
```

The Postgres spec creates a temporary schema for the suite and drops it afterward. The configured user must be allowed to create and drop schemas.

Run the MongoDB session contract:

```powershell
$env:SEARCHESS_MONGO_URI="mongodb://localhost:27017"

sbt "adapterPersistence/testOnly chess.adapter.repository.mongo.MongoSessionRepositorySpec"
```

The Mongo spec creates a temporary database for the suite and drops it afterward.

If the database environment variable is not configured, the Postgres and Mongo contract tests cancel cleanly instead of failing the normal test run.

## Why This Architecture Matters

The completed slice proves the persistence boundary works:
- the application depends only on `SessionRepository`.
- three storage technologies can satisfy the same behavior.
- database-specific tables, documents, indexes, and mappers stay in adapter packages.
- contract tests define behavior once and reuse it for every implementation.

This gives the team a repeatable pattern for the next persistence slice without coupling the application to a specific database.

## Small Cleanup Suggestions

Before moving to `GameRepository`, consider:
- Add the shared contract test pattern to the team report as the main evidence of DB independence.
- Run the Postgres and Mongo contract specs once with real local services and record the output.
- Decide whether SQLite session tests should also be migrated to the shared contract or left as legacy coverage.
- Add runtime wiring for Postgres/Mongo only when the application is ready to select those modes; the repository adapters themselves are already isolated.

# GameRepository Slice

## Purpose

`GameRepository` is the persistence slice for the current authoritative chess game state.

It stores the latest `GameState` for a `GameId`. This is snapshot-style persistence: each save replaces the current game-state snapshot for that game id.

## Scope

`GameRepository` persists:
- `gameId` association
- board state
- current player
- move history
- game status
- castling rights
- en passant state
- halfmove clock
- fullmove number

`GameRepository` does not persist:
- session metadata
- session lifecycle
- player/controller metadata
- archive records
- outbox events
- UI state
- derived legal moves
- full audit/event-sourced game history

Move history inside `GameState` is persisted because it is part of the current domain state. It is not a substitute for a later archive/history slice.

## Port Contract

The application/core layer owns the port:

```scala
trait GameRepository:
  def save(gameId: GameId, state: GameState): Either[RepositoryError, Unit]
  def load(gameId: GameId): Either[RepositoryError, GameState]
```

All methods use:

```scala
Either[RepositoryError, A]
```

The port exposes no in-memory, Slick, SQL, Mongo, BSON, JSON, row, or document types.

## Shared Behavioral Guarantees

The shared contract tests enforce these rules for every implementation:
- `save` followed by `load` returns the same application-level `GameState`.
- missing `GameId` returns `Left(RepositoryError.NotFound(...))`.
- saving again for the same `GameId` replaces the current state.
- independent `GameId`s do not overwrite each other.
- non-terminal game states round-trip.
- terminal game states round-trip.
- rule-critical fields round-trip:
  - board
  - current player
  - move history, including promotion
  - status
  - castling rights
  - en passant state
  - halfmove clock
  - fullmove number

## Implementation Summary

### InMemory

Implementation:

```text
chess.adapter.repository.InMemoryGameRepository
```

Storage shape:

```text
Map[GameId, GameState]
```

The in-memory adapter stores `GameState` directly because there is no external serialization boundary. It is useful for local runs and fast tests, but it is not durable across process restarts.

### PostgreSQL/Slick

Implementation package:

```text
chess.adapter.repository.postgres
```

Relational snapshot shape:

```text
game_states
  game_id     UUID primary key
  state_json  text/varchar
```

`state_json` is adapter-local snapshot data. It stores the complete current `GameState` as explicit JSON, including board, move history, status, castling rights, en passant state, and counters.

Slick table, row, schema, repository, and JSON mapper code stay inside the Postgres adapter package.

### MongoDB

Implementation package:

```text
chess.adapter.repository.mongo
```

Document snapshot shape:

```json
{
  "_id": "game uuid",
  "gameId": "game uuid",
  "state": {
    "currentPlayer": "Black",
    "status": {
      "type": "Ongoing",
      "inCheck": true
    },
    "board": [
      {
        "square": "e4",
        "color": "White",
        "pieceType": "Pawn"
      }
    ],
    "moveHistory": [
      {
        "from": "e2",
        "to": "e4"
      },
      {
        "from": "a7",
        "to": "a8",
        "promotion": "Queen"
      }
    ],
    "castlingRights": {
      "wk": false,
      "wq": true,
      "bk": true,
      "bq": false
    },
    "enPassant": {
      "target": "e3",
      "capturablePawn": "e4",
      "pawnColor": "White"
    },
    "halfmoveClock": 7,
    "fullmoveNumber": 12
  }
}
```

Optional fields are omitted when absent:
- no `enPassant` field when `enPassantState` is `None`
- no `promotion` field for non-promotion moves

Mongo document and mapper code stay inside the Mongo adapter package.

## Cross-Implementation Rules

All implementations must:
- preserve the application-level `GameState`.
- return `Either[RepositoryError, A]`.
- return `NotFound` for missing `GameId` lookups.
- use replace-current-state semantics for repeated saves of the same `GameId`.
- keep independent `GameId`s isolated.
- preserve all rule-critical fields.
- map storage or decode failures to `RepositoryError.StorageFailure`.
- keep database-specific types and storage models inside adapter packages.
- avoid storing session data in game-state persistence.

## Allowed Implementation Differences

These may differ by adapter and should be documented when relevant:
- durability across process restarts.
- concurrency guarantees.
- exact storage format, such as Postgres JSON text versus Mongo nested document.
- exact storage error message text.
- physical schema, collection, or index names.
- test setup strategy.
- performance characteristics.

The application-level behavior must still match the shared contract.

## How to Run the Tests

Run the full persistence module tests:

```powershell
sbt "adapterPersistence/test"
```

Run only the in-memory game repository contract:

```powershell
sbt "adapterPersistence/testOnly chess.adapter.repository.InMemoryGameRepositorySpec"
```

Run the PostgreSQL/Slick game repository contract:

```powershell
$env:SEARCHESS_POSTGRES_URL="jdbc:postgresql://localhost:5432/searchess_test"
$env:SEARCHESS_POSTGRES_USER="postgres"
$env:SEARCHESS_POSTGRES_PASSWORD="postgres"

sbt "adapterPersistence/testOnly chess.adapter.repository.postgres.PostgresGameRepositorySpec"
```

The Postgres spec creates a temporary schema for the suite and drops it afterward. The configured user must be allowed to create and drop schemas.

Run the MongoDB game repository contract:

```powershell
$env:SEARCHESS_MONGO_URI="mongodb://localhost:27017"

sbt "adapterPersistence/testOnly chess.adapter.repository.mongo.MongoGameRepositorySpec"
```

The Mongo spec creates a temporary database for the suite and drops it afterward.

If the database environment variable is not configured, the Postgres and Mongo contract tests cancel cleanly instead of failing the normal test run.

## Why This Architecture Matters

The completed slice proves that current game-state persistence is database-independent:
- the application depends only on `GameRepository`.
- in-memory, Postgres/Slick, and MongoDB adapters share the same behavior contract.
- relational and document databases use different storage shapes without changing the domain model.
- rule-critical chess state is protected by shared contract tests.

This gives the team a proven pattern for coordinated persistence through `SessionGameStore`, where session metadata and game state must be written together.

# SessionGameStore Slice

## Purpose

`SessionGameStore` is the coordinating persistence port for commands that must persist a `GameSession` and the current `GameState` as one logical write.

The assignment language uses the DAO pattern, but this port remains a domain-oriented repository boundary: the application/core layer depends on `SessionGameStore`, not on Slick, SQL, Mongo, JDBC, rows, documents, transactions, or adapter internals.

The port exists because some chess use cases change two persistence concerns at once:
- session metadata, such as lifecycle and timestamps.
- authoritative current game state, such as board, turn, status, and move history.

Writing those separately from application code would make partial updates too easy.

## When It Must Be Used

Use `SessionGameStore` whenever a command produces both an updated `GameSession` and an updated `GameState`.

Typical examples:
- creating a new game and session together.
- applying a move that updates the board and may advance the session lifecycle.
- resigning a game and finishing the session.
- any future command where the session lifecycle and game state must become visible together.

Use `SessionRepository` alone only for session-only workflows. Use `GameRepository` alone only for game-state-only workflows. Application services must not manually call both repositories for a command that requires coordinated persistence.

## What It Does Not Do

`SessionGameStore` deliberately stays small. It does not:
- expose read APIs.
- replace `SessionRepository.load`, `SessionRepository.loadByGameId`, or `GameRepository.load`.
- define a generic transaction framework.
- store archive/history/PGN/event-outbox data.
- own session or game storage models.
- introduce database-specific types into application/core.
- define Mongo-specific transaction behavior for the initial baseline.

## Port Contract

The approved port shape is:

```scala
trait SessionGameStore:
  def save(session: GameSession, state: GameState): Either[RepositoryError, Unit]
```

`Right(())` means both writes are visible through the normal repositories:
- `SessionRepository.load(session.sessionId)` returns the saved `GameSession`.
- `GameRepository.load(session.gameId)` returns the saved `GameState`.

`Left(error)` means the combined write failed and the application must not publish success events or treat either side as committed.

## Shared Behavioral Guarantees

The shared `SessionGameStoreContract` enforces:
- successful `save(session, state)` persists both sides.
- repeated saves for the same session/game association replace both sides.
- duplicate `GameId` ownership conflicts are surfaced as `RepositoryError.Conflict`.
- a rejected session write must not make the new game-state side visible.
- `GameState` rule-critical fields round-trip through the underlying `GameRepository`.
- saved sessions are visible through the underlying `SessionRepository`.

The contract intentionally tests behavior through the two read repositories, because `SessionGameStore` itself has no read methods.

## Implementation Summary

### InMemory

`InMemorySessionGameStore` coordinates the existing in-memory repositories:
- `InMemorySessionRepository`
- `InMemoryGameRepository`

It calls `sessionRepo.save(session)` first and then `gameRepo.save(session.gameId, state)`.

This is enough for the in-memory adapter because the current in-memory repositories are simple process-local stores and the game-side write does not fail in normal operation. The implementation is useful for unit tests, local composition, and fast feedback.

The in-memory implementation guarantees the logical contract used by the application, but it is not a durable transactional database.

### PostgreSQL/Slick

`PostgresSessionGameStore` uses the existing Postgres persistence shapes:
- `sessions` table for `GameSession` metadata.
- `game_states` table for the current `GameState` snapshot.

The implementation:
- maps `GameSession` with `PostgresSessionMapper`.
- serializes `GameState` with `PostgresGameStateJson`.
- writes the session row and game-state row inside one Slick `.transactionally` action.
- checks existing `GameId` ownership before writing.
- maps duplicate `GameId` ownership to `RepositoryError.Conflict`.
- maps storage, transaction, or serialization failures to `RepositoryError.StorageFailure`.

This gives the durable adapter real database transaction behavior while keeping Slick and row types inside the Postgres adapter package.

### MongoDB

`MongoSessionGameStore` coordinates the existing Mongo repositories:
- `MongoSessionRepository`
- `MongoGameRepository`

The application still depends only on `SessionGameStore`, not on Mongo driver
types. On a successful `Right(())`, both the session and game state are visible
through the normal repositories, and duplicate `GameId` ownership is surfaced as
`RepositoryError.Conflict`.

Mongo is adapter-compatible with the `SessionGameStore` port, but it is not
guarantee-equivalent to the PostgreSQL implementation. The current Mongo store
writes session metadata first and game state second. Unless Mongo transactions
are added later, a failure between those writes may leave a partial aggregate
that needs reconciliation.

## Cross-Implementation Rules

All implementations must:
- implement only `save(session, state)`.
- return `Either[RepositoryError, Unit]`.
- make both saved values observable after `Right(())`.
- reject a different `SessionId` claiming an already-owned `GameId` with `RepositoryError.Conflict`.
- preserve the `GameSession` and application-equivalent `GameState` at the repository boundary.
- avoid read APIs on `SessionGameStore`.
- keep database-specific transaction, row, document, or driver types out of application/core.

## Consistency Guarantees

PostgreSQL and MongoDB are interchangeable at the application API boundary, but
not identical at the consistency-guarantee boundary.

PostgreSQL/Slick provides the strongest current coordinated-write guarantee:
- a real database transaction commits the session and game-state rows together.
- if the transaction fails, neither write is committed.
- unique `GameId` ownership can be protected by relational constraints and conflict mapping.
- data is durable across process restarts.
- the temporary-schema contract spec can verify the adapter against a real Postgres instance.

MongoDB provides the same successful-write contract in the normal case:
- successful writes make both records visible through the repository ports.
- session-side ownership conflicts are mapped to `RepositoryError.Conflict`.
- the adapter remains replaceable behind `SessionGameStore`.

MongoDB does not currently provide rollback-atomic behavior across the two
collections. In-memory remains valuable for tests and local development, but its
guarantees are process-local and non-durable.

## How to Run the Tests

Run the full persistence module tests:

```powershell
sbt "adapterPersistence/test"
```

Run only the in-memory `SessionGameStore` contract:

```powershell
sbt "adapterPersistence/testOnly chess.adapter.repository.InMemorySessionGameStoreSpec"
```

Run the PostgreSQL/Slick `SessionGameStore` contract:

```powershell
$env:SEARCHESS_POSTGRES_URL="jdbc:postgresql://localhost:5432/searchess_test"
$env:SEARCHESS_POSTGRES_USER="postgres"
$env:SEARCHESS_POSTGRES_PASSWORD="postgres"

sbt "adapterPersistence/testOnly chess.adapter.repository.postgres.PostgresSessionGameStoreSpec"
```

The Postgres spec creates a temporary schema for the suite and drops it afterward. The configured user must be allowed to create and drop schemas.

Run the MongoDB `SessionGameStore` contract:

```powershell
$env:SEARCHESS_MONGO_URI="mongodb://localhost:27017"

sbt "adapterPersistence/testOnly chess.adapter.repository.mongo.MongoSessionGameStoreSpec"
```

If the database environment variable is not configured, the Postgres and Mongo
contract tests cancel cleanly instead of failing the normal test run.

## Why This Architecture Matters

This slice completes the core persistence story:
- `SessionRepository` owns session metadata.
- `GameRepository` owns current authoritative game state.
- `SessionGameStore` owns coordinated writes when both must change together.

The application can express its consistency requirement directly without
depending on database APIs. Each adapter is responsible for satisfying the same
application-facing behavior with its own storage technology, while documenting
where its operational guarantees differ.

This keeps the architecture small enough for a student team while still demonstrating clean ports, adapter-local database code, reusable contract tests, and meaningful consistency boundaries.

## Small Cleanup Suggestions

Before final evaluation, consider:
- Run the Postgres `SessionGameStore` contract once with a real local Postgres instance and record the result.
- Run the Postgres and Mongo repository/store contract specs with real local services if the report wants durable-adapter evidence.
- Keep SQLite-specific persistence tests documented as legacy/local coverage unless the team decides to migrate them to shared contracts later.
- Add a short report note that `SessionGameStore` coordinates proven repository behavior instead of redefining session or game storage.
