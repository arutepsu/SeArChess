# Local Container Deployment

This guide runs Envoy, the Scala Game Service, the Scala History Service, and
the Python AI service with Docker Compose. It is intended for local/dev
extraction checks only.

## Prerequisites

- Run commands from the Scala repo root: `searchess`.
- The sibling Python repo must exist at `../searchess-ai-service`.
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
| `ai-service` | `8765` | internal | Python AI inference API |

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
| `AI_PROVIDER_MODE` | `remote` | Use the Python AI service |
| `AI_REMOTE_BASE_URL` | `http://ai-service:8765` | Compose-network URL for Python AI |
| `AI_TIMEOUT_MILLIS` | `2000` | Remote AI client timeout |

The Python AI service uses:

| Variable | Value | Meaning |
|---|---|---|
| `INFERENCE_BACKEND` | `random` | Pick a random legal move for integration tests |

Inside Compose, `game-service` reaches the Python service by DNS name:
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

Trigger the Python-backed AI move:

```bash
curl -s -X POST http://127.0.0.1:10000/api/games/{gameId}/ai-move
```

A successful response is HTTP 200 with the updated game state. If the AI
service is not reachable, the Game Service returns `503 AI_PROVIDER_FAILED`.
