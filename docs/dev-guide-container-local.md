# Local Container Deployment

This guide runs the Scala Game Service beside the Python AI service with Docker
Compose. It is intended for local/dev extraction checks only.

## Prerequisites

- Run commands from the Scala repo root: `searchess`.
- The sibling Python repo must exist at `../searchess-ai-service`.
- Docker Compose must be available.

## Build And Run

```bash
docker compose up --build
```

Compose starts two services:

| Service | Container port | Host port | Purpose |
|---|---:|---:|---|
| `game-service` | `8080` | `8080` | Scala HTTP API |
| `game-service` | `9090` | `9090` | Scala WebSocket server |
| `ai-service` | `8765` | `8765` | Python AI inference API |

Check liveness from the host:

```bash
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8765/health
```

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

To verify persistence across a Game Service restart:

```bash
# Create a Human-vs-AI session and note session.gameId.
curl -s -X POST http://127.0.0.1:8080/sessions \
  -H "Content-Type: application/json" \
  -d '{"mode":"HumanVsAI"}'

# Restart only the Scala service; the named volume is preserved.
docker compose restart game-service

# Replace {gameId}; this should still return HTTP 200 with the saved game.
curl -s http://127.0.0.1:8080/games/{gameId}
```

To reset local container state:

```bash
docker compose down -v
```

## End-To-End Smoke Flow

Create a Human-vs-AI session:

```bash
curl -s -X POST http://127.0.0.1:8080/sessions \
  -H "Content-Type: application/json" \
  -d '{"mode":"HumanVsAI"}'
```

Copy the returned `session.gameId`, then submit a human move:

```bash
curl -s -X POST http://127.0.0.1:8080/games/{gameId}/moves \
  -H "Content-Type: application/json" \
  -d '{"from":"e2","to":"e4","controller":"HumanLocal"}'
```

Trigger the Python-backed AI move:

```bash
curl -s -X POST http://127.0.0.1:8080/games/{gameId}/ai-move
```

A successful response is HTTP 200 with the updated game state. If the AI
service is not reachable, the Game Service returns `503 AI_PROVIDER_FAILED`.
