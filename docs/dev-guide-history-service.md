# Local History Service Extraction Proof

This is the first concrete History extraction seam. It is intentionally small:
no broker, no production orchestration, no shared Game Service database access.

Contract reference: `docs/contracts/history-service-http-v1.md`.

## Decision Summary

- Game Service remains authoritative for sessions, game state, and closure.
- Game Service exposes `GET /archive/games/{gameId}` as the History-facing read
  contract.
- Game Service forwards terminal Game event JSON to History Service at
  `POST /internal/events/game` over HTTP in local/dev compose.
- The event is only a trigger. History then pulls the archive snapshot from Game
  Service over HTTP.
- History materializes PGN/FEN with the existing notation path and stores its
  own archive record in its own SQLite database.
- In SQLite mode, Game Service writes terminal History-facing events to a small
  SQLite outbox before a background forwarder POSTs them to History.

## Services

```bash
docker compose up --build
```

| Service | Host port | Role |
|---|---:|---|
| `envoy` | `10000` | public local/dev edge to Game Service |
| `game-service` | internal | authoritative Game Service |
| `history-service` | internal | downstream archive materializer |
| `ai-service` | internal | remote AI provider |

History Service environment:

| Variable | Value |
|---|---|
| `HISTORY_HTTP_HOST` | `0.0.0.0` |
| `HISTORY_HTTP_PORT` | `8081` |
| `GAME_SERVICE_BASE_URL` | `http://game-service:8080` |
| `HISTORY_DB_PATH` | `/history-data/history.sqlite` |
| `HISTORY_GAME_SERVICE_TIMEOUT_MILLIS` | `2000` |
| `HISTORY_ACCEPT_LEGACY_INGESTION_PATH` | `false` |

Game Service History forwarding environment:

| Variable | Value |
|---|---|
| `HISTORY_FORWARDING_ENABLED` | `true` |
| `HISTORY_SERVICE_BASE_URL` | `http://history-service:8081` |
| `HISTORY_FORWARDING_TIMEOUT_MILLIS` | `2000` |

Game Service must also run with SQLite persistence for durable forwarding:

| Variable | Value |
|---|---|
| `PERSISTENCE_MODE` | `sqlite` |
| `CHESS_DB_PATH` | `/data/searchess.sqlite` |

History persistence is mounted separately from Game Service:

```yaml
history-service-data:/history-data
```

## Archive Read Contract

Game Service:

```bash
GET http://127.0.0.1:10000/api/archive/games/{gameId}
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
SESSION_JSON=$(curl -s -X POST http://127.0.0.1:10000/api/sessions \
  -H "Content-Type: application/json" \
  -d '{}')

SESSION_ID=$(echo "$SESSION_JSON" | jq -r '.session.sessionId')
GAME_ID=$(echo "$SESSION_JSON" | jq -r '.session.gameId')

curl -s -X POST "http://127.0.0.1:10000/api/sessions/$SESSION_ID/cancel"
```

The cancel command publishes `game.session.cancelled.v1`; Game Service writes
that event to its SQLite outbox and the background forwarder delivers it to
History automatically. Verify History owns a stored archive:

```bash
docker compose exec history-service curl -s "http://127.0.0.1:8081/archives/$GAME_ID"
docker compose exec history-service ls -l /history-data
```

The History record is stored in `/history-data/history.sqlite`, not in the Game
Service SQLite file.

The Game Service outbox is stored in its own table, `history_event_outbox`,
inside `/data/searchess.sqlite`. Rows remain pending while `delivered_at` is
`NULL` and are retried by Game Service after restart.

## Manual Test Hook

`POST /events/game` remains available only when
`HISTORY_ACCEPT_LEGACY_INGESTION_PATH=true` as a local/dev compatibility hook for exercising
History directly, but it is no longer required for the normal compose proof.

## Honest Boundary

This is a real extraction step because History is now a separately runnable
process that depends on Game Service only through HTTP and event JSON. The
local/dev SQLite outbox is now durable enough to survive a temporary History
outage or Game Service restart after the outbox row is written.

This is still not production-grade event delivery. The outbox write is not in
the same transaction as the external History HTTP delivery, and delivery is
at-least-once. In SQLite mode, the Game state/session write and the durable
outbox insert are committed together; in-memory Game persistence still falls
back to best-effort forwarding because there is no durable store to reload
after restart. See `docs/architecture/game-history-outbox.md`.
