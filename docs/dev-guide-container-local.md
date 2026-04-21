# Local Container Deployment

This guide runs Envoy, the Scala Game Service, the Scala History Service, and
the Scala AI Service with Docker Compose. It is the canonical local/dev
microservice topology.

## Prerequisites

- Run commands from the Scala repo root: `searchess`.
- Docker Compose must be available.

## Build And Run

```bash
docker compose up --build
```

Compose starts four services:

| Service | Container port | Host port | Purpose |
|---|---:|---:|---|
| `envoy` | `10000` | `10000` | Public local/dev edge |
| `game-service` | `8080` | internal | Scala HTTP API |
| `game-service` | `9090` | internal | Scala WebSocket server |
| `history-service` | `8081` | internal | Scala History archive API |
| `ai-service` | `8765` | internal | Scala AI inference API |

Check liveness from the host:

```bash
curl http://127.0.0.1:10000/health
```

Normal client access should use Envoy. The service container ports are exposed
only on the Compose network.

## Edge Routing

Envoy uses static local/dev routing:

| Public path | Internal target |
|---|---|
| `GET /health` | `game-service:8080/health` |
| `/api/*` | `game-service:8080/*` with `/api` stripped |
| `/ws/*` | `game-service:9090/ws/*` with WebSocket upgrade support |

Examples:

```bash
curl -s -X POST http://127.0.0.1:10000/api/sessions \
  -H "Content-Type: application/json" \
  -d '{"mode":"HumanVsAI"}'
```

Game-to-History and Game-to-AI traffic stays direct inside the Compose network.
History and AI are not routed through Envoy.

## Environment

The compose file sets the Game Service environment explicitly:

| Variable | Value | Meaning |
|---|---|---|
| `HTTP_HOST` | `0.0.0.0` | Bind inside the container |
| `HTTP_PORT` | `8080` | Scala HTTP API port |
| `WS_ENABLED` | `true` | Start the WebSocket server |
| `WS_PORT` | `9090` | Scala WebSocket port |
| `PERSISTENCE_MODE` | `sqlite` | Use durable SQLite persistence |
| `CHESS_DB_PATH` | `/data/searchess.sqlite` | SQLite DB file path in the container |
| `EVENT_MODE` | `in-process` | Current event delivery mode |
| `AI_PROVIDER_MODE` | `remote` | Use the internal AI Service |
| `AI_REMOTE_BASE_URL` | `http://ai-service:8765` | Compose-network URL for AI |
| `AI_TIMEOUT_MILLIS` | `2000` | Remote AI client timeout |

The AI Service uses:

| Variable | Value | Meaning |
|---|---|---|
| `AI_HTTP_HOST` | `0.0.0.0` | Bind inside the container |
| `AI_HTTP_PORT` | `8765` | Internal HTTP port |
| `AI_ENGINE_ID` | `random-legal` | Engine identifier returned by the Scala AI capability |

Inside Compose, `game-service` reaches AI by DNS name:
`http://ai-service:8765`. Do not use `localhost` for container-to-container
traffic; in a container, `localhost` means the same container.

Inside Compose, `game-service` reaches History by DNS name:
`http://history-service:8081`.

## SQLite Persistence

The Game Service mounts a named volume:

```yaml
volumes:
  - game-service-data:/data
```

The database file is `/data/searchess.sqlite` inside the container. Because
`/data` is backed by the named volume, sessions and game state survive container
restarts until the volume is removed.

To inspect the mounted DB path:

```bash
docker compose exec game-service ls -l /data
```

When History forwarding is enabled, the Game Service SQLite file also owns the
`history_event_outbox` table for terminal Game -> History delivery. Local/dev
read-only inspection is available without opening SQLite manually:

```bash
curl -s http://127.0.0.1:10000/api/ops/history-outbox
curl -s http://127.0.0.1:10000/api/ops/history-outbox/pending
```

See `docs/dev-guide-game-service.md` for the response shape and limits of this
debug surface.

To verify persistence across a Game Service restart:

```bash
# Create a Human-vs-AI session and note session.gameId.
curl -s -X POST http://127.0.0.1:10000/api/sessions \
  -H "Content-Type: application/json" \
  -d '{"mode":"HumanVsAI"}'

# Restart only the Scala service; the named volume is preserved.
docker compose restart game-service

# Replace {gameId}; this should still return HTTP 200 with the saved game.
curl -s http://127.0.0.1:10000/api/games/{gameId}
```

To reset local container state:

```bash
docker compose down -v
```

## End-To-End Smoke Flow

Create a Human-vs-AI session:

```bash
curl -s -X POST http://127.0.0.1:10000/api/sessions \
  -H "Content-Type: application/json" \
  -d '{"mode":"HumanVsAI"}'
```

Copy the returned `session.gameId`, then submit a human move:

```bash
curl -s -X POST http://127.0.0.1:10000/api/games/{gameId}/moves \
  -H "Content-Type: application/json" \
  -d '{"from":"e2","to":"e4","controller":"HumanLocal"}'
```

Trigger the AI-backed move:

```bash
curl -s -X POST http://127.0.0.1:10000/api/games/{gameId}/ai-move
```

A successful response is HTTP 200 with the updated game state. If the AI
service is not reachable, the Game Service returns `503 AI_PROVIDER_FAILED`.

For a repeatable verification of the same flow, run:

```powershell
.\scripts\verify-remote-ai-flow.ps1
```

Add `-StartStack` if you want the script to run `docker compose up -d` before
checking the flow. If local PowerShell execution policy blocks direct script
execution, run:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-remote-ai-flow.ps1 -StartStack
```

The script calls Game through Envoy, verifies that the Game container is using
`AI_PROVIDER_MODE=remote` and `AI_REMOTE_BASE_URL=http://ai-service:8765`,
creates a Human-vs-AI game, submits `e2` to `e4`, triggers
`POST /api/games/{gameId}/ai-move`, and asserts that move history advances to
two moves. It also stops `ai-service` briefly to verify that Game surfaces
`503 AI_PROVIDER_FAILED` instead of falling back to the local deterministic
adapter, then starts `ai-service` again.

The same script also verifies bad provider output using the AI service's
local/dev `X-Searchess-AI-Test-Mode` header hook. It recreates only `game-service` with
`AI_REMOTE_TEST_MODE=illegal_move` and then `malformed_response`, triggers an
AI turn, and expects `422 AI_MOVE_REJECTED` in both cases. After each rejection
it fetches the game again and asserts that the persisted game state is exactly
unchanged. Use `-SkipFailurePath` to skip the AI-down check or
`-SkipRejectionPaths` to skip the bad-output checks.

## Independent Startup Paths

Compose is the canonical local topology, but each service still has an
independent runnable entry point:

| Service | Stage command | Default health path | Notes |
|---|---|---|---|
| Game | `sbt "gameService / stage"` then `apps/game-service/target/universal/stage/bin/searchess-game-service` | `GET /health` on port `8080` | History and AI are optional for process health. |
| History | `sbt "historyService / stage"` then `apps/history-service/target/universal/stage/bin/searchess-history-service` | `GET /health` on port `8081` | Game archive reads are optional for process health. |
| AI | `sbt "aiService / stage"` then `apps/ai-service/target/universal/stage/bin/searchess-ai-service` | `GET /health` on port `8765` | Internal capability service only. |

When running services directly on the host, choose non-conflicting ports if
Compose is also running. Host-run service ports are for development only; in
Compose, normal client traffic goes through Envoy at `127.0.0.1:10000`.

## Final Completion Checklist

Run this before treating the local microservice foundation as complete:

| Area | Check | Expected result |
|---|---|---|
| Startup | `sbt "gameService / stage" "historyService / stage" "aiService / stage"` | all three services stage independently |
| Startup | `docker compose up --build` | Envoy, Game, History, and AI become healthy |
| Topology | `docker compose config` | only `envoy` publishes a host `ports:` entry |
| Topology | Envoy routes | only `/health`, `/api/*`, and `/ws/*` route to Game |
| Failure | stop `history-service`, then call `GET /health` through Envoy | Game remains healthy; History delivery stays retryable in the outbox |
| Failure | stop `ai-service`, then call `GET /health` through Envoy | Game remains healthy; AI-backed moves fail cleanly with `503 AI_PROVIDER_FAILED` |
| Reliability | deliver the same terminal event more than once | History ingestion remains idempotent |
| Operability | `docker compose logs --no-color` | service logs include structured service/request/game/outbox identifiers where available |
| Operability | `GET /api/ops/history-outbox` through Envoy | outbox state is visible without direct database access |
| Contracts | review `docs/contracts/README.md` | public/internal audiences and compatibility behavior are explicit |

History and AI are internal-only in the Compose topology. Do not add Envoy
routes or host port mappings for them unless the contract map is updated first.
