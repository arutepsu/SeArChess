# SeArChess

[![Coverage Status](https://coveralls.io/repos/github/arutepsu/SeArChess/badge.svg?branch=main)](https://coveralls.io/github/arutepsu/SeArChess?branch=main)

SeArChess is a chess system being evolved from a modular monolith into a small
set of service-oriented runtimes.

Current runnable services and clients:

- `apps/game-service`
- `apps/history-service`
- `apps/web-ui`
- `apps/desktop-gui`
- `apps/tui-cli`

The repository structure now makes service ownership the default way to read the
codebase. Shared contracts and reusable libraries stay in root `modules/`;
service-owned implementation modules live beside the app that owns them.

For local/dev containers, Envoy is the single public edge entrypoint:

```text
Client -> Envoy :10000 -> Game Service
Game Service -> History Service  (internal)
Game Service -> AI Service       (internal)
```

See [docs/architecture/local-envoy-edge.md](docs/architecture/local-envoy-edge.md).

## Repository Shape

```text
apps/
  game-service/
    modules/
      ai/
      core/
      eventing/
      history-delivery/
      persistence/
      rest-http4s/
      websocket/
  history-service/
    modules/
      core/
  desktop-gui/
    modules/
      gui/
  tui-cli/
    modules/
      tui/
  startup-shared/
  web-ui/

modules/
  ai-contract/
  adapter-rest-contract/
  domain/
  game-contract/
  game-event-contract/
  notation/
  observability/
```

See [docs/architecture/service-oriented-repository-structure.md](docs/architecture/service-oriented-repository-structure.md)
for the detailed ownership classification.

## Ownership Rules

1. Game Service owns active game/session orchestration, active persistence,
   HTTP/WebSocket adapters, AI integration, internal eventing, and History
   delivery.
2. History Service owns archive ingestion, materialization, and archive
   persistence.
3. Root `modules/` contains only shared contracts and reusable libraries.
4. Shared modules must not depend on service-owned implementation modules.
5. Services may depend on shared modules and their own implementation modules.
6. Package names are currently lower priority than module ownership; some
   packages still use legacy names such as `chess.application.*`.

## Key Contracts

The canonical public/internal contract inventory is
[docs/contracts/README.md](docs/contracts/README.md).

- Public edge: [Game Service HTTP/WebSocket](docs/contracts/game-service-http-v1.md)
- Internal sync: [Game -> AI inference](docs/contracts/inference-api-v1.md)
- Internal downstream: [Game -> History ingestion](docs/contracts/history-service-http-v1.md)
- Internal async payload: [Game event JSON](docs/contracts/game-events-v1.md)

## Local Development

For the local two/three-container service proof, start with:

- [Container local guide](docs/dev-guide-container-local.md)
- [Game Service guide](docs/dev-guide-game-service.md)
- [History Service guide](docs/dev-guide-history-service.md)

The container guide is the canonical local topology reference and includes the
final microservice completion checklist.

Normal host access goes through Envoy:

```powershell
curl http://127.0.0.1:10000/health
curl -X POST http://127.0.0.1:10000/api/sessions
```

## Build

Common SBT entry points are kept stable even though source directories moved:

```powershell
sbt "gameService/test"
sbt "historyService/test"
sbt "gameService / stage"
```

The project intentionally keeps this step structural only: no runtime behavior,
contracts, routes, persistence semantics, or delivery semantics are changed by
the service-oriented directory layout.

## Persistence Migration

### Why it exists

The persistence migration feature exists to prove that the repository/DAO
abstraction is real rather than cosmetic. Sessions and game states can move
between PostgreSQL and MongoDB through application ports and adapters, not
through raw DB-to-DB copy scripts.

This also keeps migration logic aligned with the functional architecture:

- source sessions are enumerated through `SessionMigrationReader`
- source game states are loaded through `GameRepository`
- target comparison uses `SessionRepository` and `GameRepository`
- target writes go through `SessionGameStore`

`SessionMigrationReader` exists specifically so the normal
`SessionRepository` does not need a migration-only `listAll()`.

### Why it is not a microservice yet

Migration is currently an internal operational capability, not a runtime
business feature. Keeping it as an internal CLI keeps the implementation small,
reuses the existing adapters directly, and avoids premature work on HTTP admin
surfaces, authentication, deployment, and service-to-service coordination.

### Architecture

The migration service is `PersistenceMigrationService`. It owns all migration
decisions. The CLI only parses arguments, wires adapters, and prints the
resulting report.

At a high level:

- source side:
  - `SessionMigrationReader`
  - `GameRepository`
- target side:
  - `SessionRepository`
  - `GameRepository`
  - `SessionGameStore`

This means the same application-level migration logic can run across different
backends while staying DB-independent.

PostgreSQL and MongoDB are interchangeable at the application API boundary, not
identical at the consistency-guarantee boundary. The application depends on
`SessionGameStore`, not on a concrete database. PostgreSQL satisfies that port
with a real transaction around session and game-state writes. MongoDB satisfies
the same successful-write contract in the normal case, but the current adapter
uses sequential best-effort writes unless Mongo transactions are added later.
Mongo is therefore adapter-compatible, but not guarantee-equivalent to
Postgres.

### Migration Flow

1. Enumerate source `GameSession` records through `SessionMigrationReader`.
2. For each session, load the source `GameState` through the source
   `GameRepository`.
3. Compare target state through the target `SessionRepository` and
   `GameRepository`.
4. In `Execute`, write missing aggregates through `SessionGameStore`.
5. Return a `MigrationReport` summarizing the run.

### Modes

#### DryRun

- reads source sessions
- loads source game states
- inspects target state
- reports what would happen
- writes nothing

#### Execute

- reads source sessions
- loads source game states
- inspects target state
- migrates missing aggregates
- skips equivalent aggregates
- reports conflicts for different target data

#### ValidateOnly

- reads source sessions
- loads source game states
- inspects target state
- reports whether target matches source
- writes nothing

### Conflict and Idempotency Policy

`Execute` is idempotent-safe by default:

- missing target session + game state: migrate
- equivalent target session + game state: skip
- different target data: conflict
- partial target aggregate: conflict/mismatch

The migration does not overwrite different target data by default.

### Supported Paths

Current tested cross-database paths:

- PostgreSQL -> MongoDB
- MongoDB -> PostgreSQL

The service remains adapter-driven, so the core logic is not specialized to one
pair of databases.

### CLI Usage

Entry point:

- `chess.server.migration.PersistenceMigrationMain`

Examples:

```powershell
sbt "gameService/runMain chess.server.migration.PersistenceMigrationMain --from postgres --to mongo --mode dry-run"
```

```powershell
sbt "gameService/runMain chess.server.migration.PersistenceMigrationMain --from postgres --to mongo --mode execute --batch-size 200"
```

```powershell
sbt "gameService/runMain chess.server.migration.PersistenceMigrationMain --from mongo --to postgres --mode validate-only"
```

Supported arguments:

- `--from postgres|mongo`
- `--to postgres|mongo`
- `--mode dry-run|execute|validate-only`
- `--batch-size N`

### Environment Variables

PostgreSQL:

- `SEARCHESS_POSTGRES_URL`
- `SEARCHESS_POSTGRES_USER`
- `SEARCHESS_POSTGRES_PASSWORD`

MongoDB:

- `SEARCHESS_MONGO_URI`

### Testing Strategy

The migration feature is tested in layers:

- migration-core behavior tests with in-memory adapters
- shared `SessionMigrationReader` contract tests
- adapter-specific reader tests for:
  - in-memory
  - PostgreSQL
  - MongoDB
- cross-database integration tests for:
  - PostgreSQL -> MongoDB
  - MongoDB -> PostgreSQL

Database-backed tests are environment-gated and use isolated temporary schemas
or temporary Mongo databases.

Mongo reader pagination has focused regression coverage for exact batch
boundaries. The Mongo migration reader fetches `batchSize + 1` records
internally, returns at most `batchSize`, and uses the extra record only to decide
whether a real next cursor exists. This avoids false next cursors when the
source count is exactly divisible by the batch size.

### Guarantees and Limitations

Guarantees:

- migration logic stays in `PersistenceMigrationService`
- normal `SessionRepository` is not polluted with migration-only methods
- `DryRun` and `ValidateOnly` write nothing
- target writes go through `SessionGameStore`
- Postgres target writes use real transaction semantics
- Mongo target writes still go through the same `SessionGameStore` boundary, but the current Mongo
  implementation is adapter-compatible rather than guarantee-equivalent to Postgres

Limitations:

- migration is internal CLI-only today
- supported source/target backends are currently PostgreSQL and MongoDB
- Mongo `SessionGameStore` is intentionally minimal best-effort coordination:
  `Right(())` means both writes completed, but a failure after the first write may leave a partial
  aggregate and require reconciliation
- Postgres currently provides the stronger coordinated-write guarantee because
  `PostgresSessionGameStore` uses a real database transaction
- `Execute` does not automatically run a full validation pass; run `ValidateOnly`
  after `Execute` when you want explicit source/target confirmation

### Recommended Workflow

For operational use, run `DryRun`, then `Execute`, then `ValidateOnly`. The
final validation pass is explicit today; it can become automatic later if the
project needs that behavior.

### Future Extension: Admin API / Microservice

The current design is intentionally suitable for later exposure as an admin API
or dedicated migration service. The migration orchestration already lives behind
application ports; the CLI is only a thin operational shell around it.
