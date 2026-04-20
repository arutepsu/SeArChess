# Local History Service Extraction Proof

This is the first concrete History extraction seam. It is intentionally small:
no broker, no production orchestration, no shared Game Service database access.

## Decision Summary

- Game Service remains authoritative for sessions, game state, and closure.
- Game Service exposes `GET /archive/games/{gameId}` as the History-facing read
  contract.
- Game Service forwards terminal Game event JSON to History Service at
  `POST /events/game` over HTTP in local/dev compose.
- The event is only a trigger. History then pulls the archive snapshot from Game
  Service over HTTP.
- History materializes PGN/FEN with the existing notation path and stores its
  own archive record in its own SQLite database.
- The HTTP forwarding bridge is best-effort. It is useful for local extraction
  proof, but it is not durable delivery.

## Services

```bash
docker compose up --build
```

| Service | Host port | Role |
|---|---:|---|
| `game-service` | `8080` | authoritative Game Service |
| `history-service` | `8081` | downstream archive materializer |
| `ai-service` | `8765` | remote AI provider |

History Service environment:

| Variable | Value |
|---|---|
| `HISTORY_HTTP_HOST` | `0.0.0.0` |
| `HISTORY_HTTP_PORT` | `8081` |
| `GAME_SERVICE_BASE_URL` | `http://game-service:8080` |
| `HISTORY_DB_PATH` | `/history-data/history.sqlite` |
| `HISTORY_GAME_SERVICE_TIMEOUT_MILLIS` | `2000` |

Game Service History forwarding environment:

| Variable | Value |
|---|---|
| `HISTORY_FORWARDING_ENABLED` | `true` |
| `HISTORY_SERVICE_BASE_URL` | `http://history-service:8081` |
| `HISTORY_FORWARDING_TIMEOUT_MILLIS` | `2000` |

History persistence is mounted separately from Game Service:

```yaml
history-service-data:/history-data
```

## Archive Read Contract

Game Service:

```bash
GET http://127.0.0.1:8080/archive/games/{gameId}
```

Responses:

| Status | Meaning |
|---:|---|
| `200` | archive snapshot is available |
| `400` | malformed game id |
| `404` | no game/session exists |
| `409` | game exists but is not closed |
| `500` | storage failure |

The payload includes IDs, mode, controllers, closure, timestamps, and a final
state that is sufficient for History to re-materialize FEN and PGN without
reading Game Service storage.

## Local Automatic Flow

Compose enables the local/dev HTTP bridge from Game Service to History Service.
The service name `history-service` is resolved on the compose network, so Game
Service does not call `localhost` for History.

Create and cancel a session:

```bash
SESSION_JSON=$(curl -s -X POST http://127.0.0.1:8080/sessions \
  -H "Content-Type: application/json" \
  -d '{}')

SESSION_ID=$(echo "$SESSION_JSON" | jq -r '.session.sessionId')
GAME_ID=$(echo "$SESSION_JSON" | jq -r '.session.gameId')

curl -s -X POST "http://127.0.0.1:8080/sessions/$SESSION_ID/cancel"
```

The cancel command publishes `game.session.cancelled.v1`; Game Service forwards
that event to History automatically. Verify History owns a stored archive:

```bash
curl -s "http://127.0.0.1:8081/archives/$GAME_ID"
docker compose exec history-service ls -l /history-data
```

The History record is stored in `/history-data/history.sqlite`, not in the Game
Service SQLite file.

## Manual Test Hook

`POST /events/game` remains available as a local/dev test hook for exercising
History directly, but it is no longer required for the normal compose proof.

## Honest Boundary

This is a real extraction step because History is now a separately runnable
process that depends on Game Service only through HTTP and event JSON. It is
not yet durable event delivery: if the HTTP bridge cannot reach History, the
failure is logged/absorbed and gameplay still succeeds. The missing production
piece is a durable delivery mechanism such as an outbox or broker-backed
subscriber.
