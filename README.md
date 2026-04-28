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

## Local Persistence Infrastructure

`docker-compose.yml` is the normal local development, persistence demo, and
uni-server-style stack. It starts Envoy, Game Service, History Service, AI
Service, PostgreSQL, and MongoDB. The Game Service image is
persistence-neutral; persistence mode is runtime configuration.

Game Service uses Postgres by default in Compose through
`PERSISTENCE_MODE=${PERSISTENCE_MODE:-postgres}` and receives the local
`SEARCHESS_POSTGRES_*` connection settings. Set `PERSISTENCE_MODE=mongo` or
`PERSISTENCE_MODE=mongodb` when you want to restart Game Service against
migrated Mongo data.

SQLite support remains available, but it is opt-in through
`docker-compose.sqlite.yml`. That override sets `PERSISTENCE_MODE=sqlite`,
`CHESS_DB_PATH=/data/searchess.sqlite`, and mounts a small `/data` volume for
the Game Service container.

Automated persistence integration tests use Testcontainers instead. Docker must
be running, but Docker Compose does not need to be started for those tests.
Testcontainers creates disposable PostgreSQL and MongoDB containers for the test
process and removes them afterward. Compose remains for manual demos and the
uni-server path.

The credentials in this file are local/demo credentials only:

- database/user/password: `searchess` for PostgreSQL
- unauthenticated local MongoDB on the Compose network/localhost

Do not reuse these values for production.

Start the full local/dev/demo stack:

```powershell
docker compose up -d --build
```

Stop the stack while keeping data:

```powershell
docker compose down
```

Reset all persisted data:

```powershell
docker compose down -v
```

PostgreSQL stores data in the named Docker volume
`searchess_postgres_data`, mounted at `/var/lib/postgresql/data`.
MongoDB stores documents in the named Docker volume
`searchess_mongo_data`, mounted at `/data/db`.
History Service stores its separate history database in the
`history-service-data` volume. Game Service persistence is Postgres or Mongo;
History Service persistence is intentionally separate and is not migrated in
the game persistence demo.

Set the local adapter environment variables in PowerShell:

```powershell
$env:SEARCHESS_POSTGRES_URL="jdbc:postgresql://localhost:5432/searchess"
$env:SEARCHESS_POSTGRES_USER="searchess"
$env:SEARCHESS_POSTGRES_PASSWORD="searchess"
$env:SEARCHESS_MONGO_URI="mongodb://localhost:27017/searchess"
```

Postgres is the default Game Service runtime persistence backend when
`PERSISTENCE_MODE` is omitted. The default runtime requires the
`SEARCHESS_POSTGRES_*` variables above and runs Flyway automatically before the
Postgres repositories are constructed. MongoDB can be selected explicitly with
`PERSISTENCE_MODE=mongo` or `PERSISTENCE_MODE=mongodb`; it uses
`SEARCHESS_MONGO_URI` and optional `SEARCHESS_MONGO_DATABASE`, initializes
adapter indexes, and is not Flyway-managed. For lightweight local runs without
using the Game Service Postgres default, select a smaller backend explicitly:

```powershell
$env:PERSISTENCE_MODE="sqlite"
# or
$env:PERSISTENCE_MODE="in-memory"
```

Run the container stack in SQLite mode:

```powershell
docker compose -f docker-compose.yml -f docker-compose.sqlite.yml up -d --build
```

The main stack publishes Envoy on `http://127.0.0.1:10000`. In Postgres mode,
runtime startup runs Flyway and sessions/game states are persisted to Postgres.
In Mongo mode, the same stack uses
`SEARCHESS_MONGO_URI=mongodb://mongo:27017/searchess` and the `sessions` and
`games` collections. Mongo is not Flyway-managed and is not
transaction-equivalent to Postgres for combined session/game writes.

The same Compose file can be reused on the uni server later. For commands run
on the host through VPN, keep using the published localhost ports. For services
running inside the same Docker Compose network, use Docker service names:

```text
jdbc:postgresql://postgres:5432/searchess
mongodb://mongo:27017/searchess
```

Do not hardcode university hostnames or put real secrets in this repository.

### PostgreSQL Schema Migration

PostgreSQL schema lifecycle is managed by Flyway. Slick remains the database
access and table-mapping layer used by the repositories; it should not create
runtime Postgres tables as a hidden side effect. MongoDB is document storage and
is not managed by Flyway.

Run the Postgres schema migration explicitly after the persistence containers
are up and the `SEARCHESS_POSTGRES_*` environment variables are set:

```powershell
sbt "gameService/runMain chess.server.migration.PostgresSchemaMigrationMain"
```

The migration CLI also runs this Flyway step before it accesses Postgres as a
source or target, so an empty local/demo Postgres database can be prepared
before a migration dry run. The initial Flyway script lives at
`apps/game-service/modules/persistence/src/main/resources/db/migration/postgres/V1__create_session_persistence.sql`.

Normal Game Service startup also runs the same Flyway initializer when the
active runtime backend is Postgres. Repository constructors remain mapping and
query wiring only; schema evolution is an explicit infrastructure startup step.

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

The report is deterministic and intended for audit/readout use. It includes
source and target store names, mode, start/finish timestamps, duration, batch
size, scanned/migrated/skipped/conflict/failure counts, validation result for
`ValidateOnly`, and a final status of `Success`, `CompletedWithConflicts`, or
`Failed`.

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

Execute and immediately validate if execution succeeds:

```powershell
sbt "gameService/runMain chess.server.migration.PersistenceMigrationMain --from postgres --to mongo --mode execute --batch-size 200 --validate-after-execute"
```

```powershell
sbt "gameService/runMain chess.server.migration.PersistenceMigrationMain --from mongo --to postgres --mode validate-only"
```

With the main Compose stack, a professor-ready API flow is:

```powershell
.\scripts\demo\persistence-migration-demo.ps1
```

The script resets Compose volumes by default, starts the stack, creates a real
game through Envoy, makes `e2 -> e4`, verifies Postgres, runs Postgres -> Mongo
dry-run and execute with validation, switches Game Service to Mongo, loads the
same game, and reruns execute to show idempotency. Use `-NoReset`, `-SkipBuild`,
or `-ReturnToPostgres` when you want to adjust that flow.

The same flow can be run manually:

```powershell
docker compose up -d --build

$createBody = @{ mode = "HumanVsHuman" } | ConvertTo-Json
$created = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:10000/api/sessions -ContentType "application/json" -Body $createBody
$gameId = $created.game.gameId

$moveBody = @{ from = "e2"; to = "e4"; controller = "HumanLocal" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:10000/api/games/$gameId/moves" -ContentType "application/json" -Body $moveBody

docker compose exec postgres psql -U searchess -d searchess -c "select count(*) as sessions from sessions; select count(*) as game_states from game_states;"
```

For the Web UI presentation, start the Vite UI separately and let it use its
default Envoy base URL:

```powershell
cd apps/web-ui
npm run dev
```

Then run migration commands from the host against the demo databases:

```powershell
$env:SEARCHESS_POSTGRES_URL="jdbc:postgresql://localhost:5432/searchess"
$env:SEARCHESS_POSTGRES_USER="searchess"
$env:SEARCHESS_POSTGRES_PASSWORD="searchess"
$env:SEARCHESS_MONGO_URI="mongodb://localhost:27017/searchess"

sbt "gameService/runMain chess.server.migration.PersistenceMigrationMain --from postgres --to mongo --mode dry-run"
sbt "gameService/runMain chess.server.migration.PersistenceMigrationMain --from postgres --to mongo --mode execute --validate-after-execute"
sbt "gameService/runMain chess.server.migration.PersistenceMigrationMain --from postgres --to mongo --mode validate-only"
sbt "gameService/runMain chess.server.migration.PersistenceMigrationMain --from postgres --to mongo --mode execute --validate-after-execute"
```

The second execute demonstrates idempotency: equivalent target aggregates are
skipped rather than overwritten.

To prove the migrated data is usable by the normal runtime, restart only
Game Service with Mongo selected:

```powershell
$env:PERSISTENCE_MODE="mongo"
docker compose up -d --force-recreate game-service

Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:10000/api/games/$gameId"
Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:10000/api/sessions"
```

Unset `PERSISTENCE_MODE` before recreating the service again when you want to
return to the Postgres default:

```powershell
Remove-Item Env:PERSISTENCE_MODE
docker compose up -d --force-recreate game-service
```

Supported arguments:

- `--from postgres|mongo`
- `--to postgres|mongo`
- `--mode dry-run|execute|validate-only`
- `--batch-size N`
- `--format text|json`
- `--validate-after-execute` with `--mode execute`

The CLI prints a concise migration report at the end of each run. Text output
is the default for operator readability; JSON output is available for simple
archiving or scripting with `--format json`.

Example text report:

```text
Migration report
Status: Success
Source store: postgres
Target store: mongo
Mode: Execute
Scanned: 42
Migrated: 42
Skipped equivalent: 0
Conflicts: 0
Failed: 0
Validation result: not applicable
```

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

The persistence module also includes Testcontainers-backed specs that do not
require `SEARCHESS_POSTGRES_*` or `SEARCHESS_MONGO_URI`. They start temporary
PostgreSQL and MongoDB containers automatically when Docker is available. The
Postgres Testcontainers fixture initializes schema with Flyway before
repositories are used; Mongo Testcontainers setup initializes adapter indexes
only and is not Flyway-managed.

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

For operational use, either run `DryRun`, then `Execute`, then `ValidateOnly`,
or run `DryRun` followed by `Execute --validate-after-execute`. The inline
validation pass only runs after a successful execute report; failed execution
reports are returned without running validation.

### Future Extension: Admin API / Microservice

The current design is intentionally suitable for later exposure as an admin API
or dedicated migration service. The migration orchestration already lives behind
application ports; the CLI is only a thin operational shell around it.
