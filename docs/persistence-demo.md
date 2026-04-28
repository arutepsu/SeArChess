# Persistence Demo

This demo uses the normal `docker-compose.yml` stack: Envoy, Game Service,
History Service, AI Service, PostgreSQL, and MongoDB. It is intended for local
development, professor presentation, and later uni-server-style use over VPN.
The credentials are local/demo credentials only.

Docker Compose and Testcontainers serve different purposes. This document uses
Docker Compose for manual demos. Automated integration tests use
Testcontainers: Docker must be running, but Compose services do not need to be
started for those tests.

The Game Service Docker image is persistence-neutral. Persistence mode is
runtime configuration:

- default Compose runtime: Postgres
- Mongo runtime switch: `PERSISTENCE_MODE=mongo` or `PERSISTENCE_MODE=mongodb`
- SQLite opt-in override: `docker-compose.sqlite.yml`

## Repeatable Script

For a presentation-safe end-to-end run, use the scripted demo from the
repository root:

```powershell
.\scripts\demo\persistence-migration-demo.ps1
```

The script resets Compose volumes by default, starts the stack, creates a real
HumanVsHuman game through Envoy, makes `e2 -> e4`, verifies Postgres rows, runs
Postgres -> Mongo dry-run and execute with validation, switches Game Service to
Mongo, loads the same game through the normal API, and reruns execute to show
idempotency.

Prerequisites:

- Docker must be running.
- PowerShell must be available.
- `sbt` must be available on `PATH`.

Useful options:

```powershell
.\scripts\demo\persistence-migration-demo.ps1 -NoReset
.\scripts\demo\persistence-migration-demo.ps1 -SkipBuild
.\scripts\demo\persistence-migration-demo.ps1 -ReturnToPostgres
.\scripts\demo\persistence-migration-demo.ps1 -LeaveMongo
```

`-NoReset` keeps existing volumes. `-SkipBuild` starts existing images without
rebuilding. The script leaves Game Service on Mongo by default after proving
the switched runtime can load the migrated game; use `-ReturnToPostgres` to
restore the default at the end.

## Compose Files

Start the normal stack:

```powershell
docker compose up -d --build
```

`docker-compose.yml` starts:

- envoy
- game-service
- history-service
- ai-service
- postgres
- mongo

Game Service receives:

```text
PERSISTENCE_MODE=${PERSISTENCE_MODE:-postgres}
SEARCHESS_POSTGRES_URL=jdbc:postgresql://postgres:5432/searchess
SEARCHESS_POSTGRES_USER=searchess
SEARCHESS_POSTGRES_PASSWORD=searchess
SEARCHESS_MONGO_URI=mongodb://mongo:27017/searchess
```

Mongo runtime uses the `sessions` and `games` collections. It initializes
adapter indexes, but it is not Flyway-managed and it does not provide the same
combined-write transaction guarantee as Postgres unless Mongo transactions are
added later.

History Service remains in the main stack and keeps its own separate SQLite
history database in the `history-service-data` volume. That history persistence
is not part of the Game Service Postgres/Mongo migration demo.

Use SQLite explicitly when you want the lightweight Game Service container
mode:

```powershell
docker compose -f docker-compose.yml -f docker-compose.sqlite.yml up -d --build
```

## Host Environment

For migration commands run on the host:

```powershell
$env:SEARCHESS_POSTGRES_URL="jdbc:postgresql://localhost:5432/searchess"
$env:SEARCHESS_POSTGRES_USER="searchess"
$env:SEARCHESS_POSTGRES_PASSWORD="searchess"
$env:SEARCHESS_MONGO_URI="mongodb://localhost:27017/searchess"
```

For services running inside the Compose network, use service names:

```text
jdbc:postgresql://postgres:5432/searchess
mongodb://mongo:27017/searchess
```

## PostgreSQL Schema

Flyway manages PostgreSQL schema evolution. Slick remains the access/mapping
layer for repository code, and MongoDB is not managed by Flyway.

Game Service startup runs Flyway automatically when the runtime backend is
Postgres. The persistence migration CLI also runs the same Flyway initializer
before it uses Postgres as either source or target.

You can manually initialize Postgres if needed:

```powershell
sbt "gameService/runMain chess.server.migration.PostgresSchemaMigrationMain"
```

This applies:

```text
apps/game-service/modules/persistence/src/main/resources/db/migration/postgres/V1__create_session_persistence.sql
```

## Create Real Source Data

Start the stack:

```powershell
docker compose up -d --build
```

For a Web UI presentation, start the Vite UI separately. Its default API base
URL is Envoy at `http://localhost:10000`.

```powershell
cd apps/web-ui
npm run dev
```

For a direct API presentation through Envoy, create a real session and make a
real move through Game Service:

```powershell
$createBody = @{ mode = "HumanVsHuman" } | ConvertTo-Json
$created = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:10000/api/sessions -ContentType "application/json" -Body $createBody
$gameId = $created.game.gameId

$moveBody = @{ from = "e2"; to = "e4"; controller = "HumanLocal" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:10000/api/games/$gameId/moves" -ContentType "application/json" -Body $moveBody
```

Verify that Postgres contains runtime data:

```powershell
docker compose exec postgres psql -U searchess -d searchess -c "select count(*) as sessions from sessions; select count(*) as game_states from game_states;"
```

## Migrate Postgres to Mongo

Run these from the host after source data exists:

```powershell
$env:SEARCHESS_POSTGRES_URL="jdbc:postgresql://localhost:5432/searchess"
$env:SEARCHESS_POSTGRES_USER="searchess"
$env:SEARCHESS_POSTGRES_PASSWORD="searchess"
$env:SEARCHESS_MONGO_URI="mongodb://localhost:27017/searchess"
```

Dry run:

```powershell
sbt "gameService/runMain chess.server.migration.PersistenceMigrationMain --from postgres --to mongo --mode dry-run"
```

Execute and validate:

```powershell
sbt "gameService/runMain chess.server.migration.PersistenceMigrationMain --from postgres --to mongo --mode execute --validate-after-execute"
```

Validate only:

```powershell
sbt "gameService/runMain chess.server.migration.PersistenceMigrationMain --from postgres --to mongo --mode validate-only"
```

Execute again for idempotency:

```powershell
sbt "gameService/runMain chess.server.migration.PersistenceMigrationMain --from postgres --to mongo --mode execute --validate-after-execute"
```

The second execute should report equivalent target data as skipped rather than
overwriting it.

## Restart Runtime on Mongo

After migration, restart only Game Service with Mongo selected:

```powershell
$env:PERSISTENCE_MODE="mongo"
docker compose up -d --force-recreate game-service
```

Load the same game/session through the normal runtime API:

```powershell
Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:10000/api/games/$gameId"
Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:10000/api/sessions"
```

For Web UI presentation, refresh the Vite app after the service is recreated.
Unset `PERSISTENCE_MODE` before recreating Game Service again to return to the
Postgres default:

```powershell
Remove-Item Env:PERSISTENCE_MODE
docker compose up -d --force-recreate game-service
```

## Optional Admin Route

The CLI commands above can also be triggered through a backend HTTP route.
The route is disabled by default. Both `MIGRATION_ADMIN_ENABLED` and
`MIGRATION_ADMIN_TOKEN` must be set together — Game Service will refuse to
start if `MIGRATION_ADMIN_ENABLED=true` without a token configured.

Every request to `POST /admin/migrations` must supply the token as:

```text
X-Admin-Token: <configured token>
```

Missing or incorrect tokens return `401 Unauthorized`.

Enable it for the compose stack:

```powershell
$env:MIGRATION_ADMIN_ENABLED="true"
$env:MIGRATION_ADMIN_TOKEN="local-demo-token"
docker compose up -d --force-recreate game-service
```

Dry run via HTTP:

```powershell
$body = @{ source = "postgres"; target = "mongo"; mode = "dry-run" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:10000/admin/migrations -Headers @{ "X-Admin-Token" = $env:MIGRATION_ADMIN_TOKEN } -ContentType "application/json" -Body $body
```

Execute via HTTP:

```powershell
$body = @{
  source = "postgres"
  target = "mongo"
  mode = "execute"
  confirmation = "MIGRATE"
  validateAfterExecute = $true
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:10000/admin/migrations -Headers @{ "X-Admin-Token" = $env:MIGRATION_ADMIN_TOKEN } -ContentType "application/json" -Body $body
```

The response is the same structured `MigrationReport` JSON as `--format json`
CLI output.

**Security note:** `X-Admin-Token` is a shared-secret scheme for demo and
internal operator use only — it is not production-grade authentication.
Production deployments require proper authentication and authorization before
enabling this route. The token value must be kept secret and must not be
committed to source control.

## Persistence Admin UI

The Web UI bundle includes a separate internal operations page for migration.
It is reachable only by direct URL; it is not linked from the player homepage
or any gameplay screen.

```text
Official player Web UI: http://localhost:5173/
Persistence Admin Tool: http://localhost:5173/admin/persistence
Backend migration route: POST http://localhost:10000/admin/migrations
```

Start the stack with both `MIGRATION_ADMIN_ENABLED=true` and
`MIGRATION_ADMIN_TOKEN` set (e.g. `local-demo-token` locally), then start the
Vite UI and navigate directly to `http://localhost:5173/admin/persistence`.

Enter the configured `MIGRATION_ADMIN_TOKEN` value in the **Admin Token** field
on the page. The token is held in browser memory only and is not persisted
across page reloads.

For Execute mode, both the admin token and the `MIGRATE` confirmation text are
required: the token proves operator access; the confirmation prevents accidental
writes.

## Stop and Reset

Stop the stack while keeping data:

```powershell
docker compose down
```

Reset all data:

```powershell
docker compose down -v
```

The data volumes are:

- Game Service Postgres data: `searchess_postgres_data`
- Mongo data: `searchess_mongo_data`
- History Service data: `history-service-data`
