# Persistence Demo

This demo uses real local PostgreSQL and MongoDB containers. It is intended for
local development, professor presentation, and later uni-server demo use over
VPN. The credentials are local/demo credentials only.

Docker Compose and Testcontainers serve different purposes. This document uses
Docker Compose for manual demos and the later uni-server flow. Automated
integration tests use Testcontainers: Docker must be running, but Compose
services do not need to be started for those tests.

The Game Service Docker image is persistence-neutral. It no longer chooses
SQLite in the Dockerfile. Persistence mode is runtime configuration:

- application default: Postgres when `PERSISTENCE_MODE` is unset
- old local app compose: explicit `PERSISTENCE_MODE=sqlite`
- professor demo compose: Postgres through `SEARCHESS_POSTGRES_*`

## Compose Files

Use the DB-only stack for host-run Game Service and migration CLI work:

```powershell
docker compose -f docker-compose.persistence.yml up -d
```

Use the containerized Postgres demo stack for API/Web UI presentation:

```powershell
docker compose -f docker-compose.demo.yml up -d --build
```

`docker-compose.persistence.yml` starts only Postgres and Mongo. It remains
useful for host-run Game Service, migration CLI runs, and database smoke tests.

`docker-compose.demo.yml` starts Envoy, Game Service, Postgres, Mongo, and AI
service. It omits `PERSISTENCE_MODE`, so Game Service uses the application
Postgres default. It passes:

```text
SEARCHESS_POSTGRES_URL=jdbc:postgresql://postgres:5432/searchess
SEARCHESS_POSTGRES_USER=searchess
SEARCHESS_POSTGRES_PASSWORD=searchess
SEARCHESS_MONGO_URI=mongodb://mongo:27017/searchess
```

The existing `docker-compose.yml` remains the local microservice proof with an
explicit SQLite override:

```text
PERSISTENCE_MODE=sqlite
CHESS_DB_PATH=/data/searchess.sqlite
```

## Host Environment

For migration commands run on the host:

```powershell
$env:SEARCHESS_POSTGRES_URL="jdbc:postgresql://localhost:5432/searchess"
$env:SEARCHESS_POSTGRES_USER="searchess"
$env:SEARCHESS_POSTGRES_PASSWORD="searchess"
$env:SEARCHESS_MONGO_URI="mongodb://localhost:27017/searchess"
```

For services running inside a Compose network, use service names:

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

You can manually initialize a DB-only Postgres volume if needed:

```powershell
sbt "gameService/runMain chess.server.migration.PostgresSchemaMigrationMain"
```

This applies:

```text
apps/game-service/modules/persistence/src/main/resources/db/migration/postgres/V1__create_session_persistence.sql
```

## Create Real Source Data

Start the demo stack:

```powershell
docker compose -f docker-compose.demo.yml up -d --build
```

For a Web UI presentation, start the Vite UI separately. Its default API base
URL is Envoy at `http://localhost:10000`.

```powershell
cd apps/web-ui
npm run dev
```

For a direct API presentation, create a real session and make a real move
through Game Service:

```powershell
$created = curl.exe -s -X POST http://127.0.0.1:8080/sessions -H "Content-Type: application/json" -d "{\"mode\":\"HumanVsHuman\"}"
$gameId = ($created | ConvertFrom-Json).game.gameId
curl.exe -s -X POST "http://127.0.0.1:8080/games/$gameId/moves" -H "Content-Type: application/json" -d "{\"from\":\"e2\",\"to\":\"e4\",\"controller\":\"HumanLocal\"}"
```

Direct Game Service routes are unprefixed. If the request goes through Envoy,
use `/api/sessions` and `/api/games/{gameId}/moves`.

Verify that Postgres contains runtime data:

```powershell
docker compose -f docker-compose.demo.yml exec postgres psql -U searchess -d searchess -c "select count(*) as sessions from sessions; select count(*) as game_states from game_states;"
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

## Stop and Reset

Stop the demo stack while keeping data:

```powershell
docker compose -f docker-compose.demo.yml down
```

Reset demo data:

```powershell
docker compose -f docker-compose.demo.yml down -v
```

For the DB-only stack, use the same commands with `docker-compose.persistence.yml`.
