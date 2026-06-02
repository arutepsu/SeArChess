# Redis Streams History Delivery

Status: local/dev validated path  
Scope: terminal game events that trigger history archive materialization

Game Service publishes archive ingestion requests to Redis Streams when
`HISTORY_DELIVERY_MODE=redis-stream`. History Service consumes the same stream
when `HISTORY_INGESTION_MODE=redis-stream`.

HTTP ingestion remains available as a fallback with `http` mode.

## Stream Contract

Stream: `searchess.history.archives`  
Consumer group: `history-service`

Each stream entry is an archive delivery envelope:

| Field | Meaning |
|---|---|
| `eventId` | UUID for this delivery attempt |
| `eventType` | `history.archive.requested` |
| `eventVersion` | `1` |
| `occurredAt` | UTC instant |
| `gameId` | game UUID |
| `sourceEventType` | terminal game event type, for diagnostics |
| `payloadJson` | existing terminal Game event JSON accepted by History ingestion |

Only terminal archive triggers are published:

- `game.finished.v1`
- `game.resigned.v1`
- `game.session.cancelled.v1`

`game.created`, `move.played`, AI events, and websocket notifications are not
part of this stream.

## Processing

History Service creates the consumer group if it is missing, reads with
`XREADGROUP`, calls the existing `HistoryIngestionService`, and acknowledges
with `XACK` only after ingestion succeeds. Ingestion writes through
`SlickPostgresArchiveRepository` into `history.history_archives`.

Delivery is at-least-once. History ingestion remains idempotent by checking for
an existing archive by `game_id` and by upserting on `game_id`.

## Fallback

`HISTORY_DELIVERY_MODE=http` keeps the older direct HTTP publisher available.
The legacy SQLite outbox is still present for SQLite game-service development
mode, but the k3d and canonical compose path use Redis Streams.
