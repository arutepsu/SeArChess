# History Service HTTP Contract v1

Status: internal downstream local/dev extraction contract  
Owner: History Service  
Base path: `/`  
Content type: `application/json`

This document describes the current HTTP boundary of the standalone History
Service. It reflects the implementation in `apps/history-service` and
`apps/history-service/modules/core`; it does not introduce new endpoints or stronger delivery
guarantees than the code provides.

## Service Role

History is downstream of Game Service. This is not a public edge contract in
the current Compose/Envoy topology.

- Game Service owns active game/session state.
- Game Service exposes archive snapshots at `GET /archive/games/{gameId}`.
- Game Service emits terminal event JSON and, in SQLite mode, stores those
  events in its own durable outbox before forwarding them.
- History receives terminal event JSON at `POST /internal/events/game`.
- History uses the event only as a trigger, then calls Game Service over HTTP
  to fetch the archive snapshot.
- History materializes and owns archival records in its own SQLite database.

History must not read Game Service SQLite tables.

## Runtime Configuration

| Variable | Default | Meaning |
|---|---|---|
| `HISTORY_HTTP_HOST` | `0.0.0.0` | bind host |
| `HISTORY_HTTP_PORT` | `8081` | bind port |
| `GAME_SERVICE_BASE_URL` | `http://127.0.0.1:8080` | base URL used for `GET /archive/games/{gameId}` |
| `HISTORY_DB_PATH` | `history.sqlite` | History-owned SQLite database path |
| `HISTORY_GAME_SERVICE_TIMEOUT_MILLIS` | `2000` | timeout for Game archive snapshot fetch |
| `HISTORY_ACCEPT_LEGACY_INGESTION_PATH` | `false` | opt-in temporary alias for `POST /events/game` |

In Compose, `GAME_SERVICE_BASE_URL` should use the service name:
`http://game-service:8080`.

## Common Error Shape

All route-level errors are JSON:

```json
{
  "code": "MACHINE_READABLE_CODE",
  "message": "Human-readable detail"
}
```

UUID path values are plain UUID strings. Invalid UUIDs return `400 BAD_REQUEST`.

## Terminal Game Event Input

`POST /internal/events/game` accepts terminal Game event JSON using the Game
event wire contract. Only these event types are accepted:

- `game.finished.v1`
- `game.resigned.v1`
- `game.session.cancelled.v1`

The fields History requires from the event are:

```json
{
  "type": "game.session.cancelled.v1",
  "sessionId": "00000000-0000-0000-0000-000000000101",
  "gameId": "00000000-0000-0000-0000-000000000102"
}
```

Additional fields present on `game.finished.v1` or `game.resigned.v1` are part
of the Game event contract, but History does not use them to materialize the
archive. The authoritative closure details come from Game Service archive
snapshot retrieval.

Unsupported event types, including non-terminal Game events, return
`400 INVALID_EVENT`.

## Archive Record Output

History stores and returns materialized archive records:

```json
{
  "gameId": "00000000-0000-0000-0000-000000000102",
  "sessionId": "00000000-0000-0000-0000-000000000101",
  "mode": "HumanVsHuman",
  "whiteController": "HumanLocal",
  "blackController": "HumanLocal",
  "closure": {
    "kind": "Cancelled",
    "winner": null,
    "drawReason": null
  },
  "pgn": null,
  "finalFen": "4k3/8/8/8/8/8/8/4K3 w - - 0 1",
  "createdAt": "2026-04-20T09:00:00Z",
  "closedAt": "2026-04-20T09:01:00Z",
  "materializedAt": "2026-04-20T09:01:02Z"
}
```

`closure.kind` is one of:

- `Checkmate`
- `Resigned`
- `Draw`
- `Cancelled`

`pgn` may be `null` for cancelled sessions with no moves. `finalFen` is present
when materialization succeeds.

## Endpoints

### GET /health

Liveness check.

Response `200 OK`:

```json
{ "status": "ok" }
```

This is process liveness only. It does not verify Game Service reachability or
SQLite readiness.

### POST /internal/events/game

Ingest a terminal Game event trigger.

Request body: terminal Game event JSON.

On success, History:

1. parses the terminal event,
2. calls `GET {GAME_SERVICE_BASE_URL}/archive/games/{gameId}`,
3. materializes an `ArchiveRecord`,
4. upserts it into History SQLite by `gameId`,
5. returns the materialized record.

Response `201 Created`: `ArchiveRecord`

Errors:

| Status | Code | Meaning |
|---:|---|---|
| `400` | `INVALID_EVENT` | malformed JSON, missing required event field, invalid UUID, or unsupported event type |
| `404` | `ARCHIVE_NOT_FOUND` | Game Service returned no archive snapshot source for `gameId` |
| `409` | `ARCHIVE_NOT_READY` | Game Service says the game/session is not closed yet |
| `422` | `MATERIALIZATION_FAILED` | archive snapshot was fetched, but FEN/PGN/materialization failed |
| `502` | `GAME_SERVICE_FETCH_FAILED` | Game archive fetch failed due to transport or unexpected Game response |
| `500` | `PERSISTENCE_FAILED` | History SQLite write failed |

### POST /events/game

Temporary compatibility alias for older local/dev callers.

This path is disabled by default. It is available only when
`HISTORY_ACCEPT_LEGACY_INGESTION_PATH=true`, and it should not be routed through
the public edge. New callers must use `POST /internal/events/game`.

### GET /archives/{gameId}

Read the History-owned archive record for a game.

This endpoint is internal for now. If History archive reads become public,
introduce a separate public History archive contract/version before exposing it
at the edge.

Response `200 OK`: `ArchiveRecord`

Errors:

| Status | Code | Meaning |
|---:|---|---|
| `400` | `BAD_REQUEST` | invalid UUID path value |
| `404` | `ARCHIVE_NOT_FOUND` | History has no stored archive for `gameId` |
| `500` | `PERSISTENCE_FAILED` | History SQLite read failed |

## Idempotency And Delivery Semantics

History persistence is upsert-based on `gameId`. Re-delivering the same terminal
event after a successful prior ingestion should leave one archive row for that
game, replacing the stored record with the newly materialized value.

This supports at-least-once delivery from the Game Service outbox.

This does not mean exactly-once delivery:

- Game may deliver the same terminal event more than once.
- History may call Game archive retrieval more than once for the same game.
- `materializedAt` may change across repeated ingestions.
- There is no broker offset, consumer group, or global event ordering contract.

## Failure Behavior

If History cannot fetch the Game archive snapshot, it does not write an archive
record and returns an error.

If History persistence fails, the request returns `500 PERSISTENCE_FAILED`.
Game's durable outbox forwarder treats non-2xx responses as failed delivery and
leaves the outbox row pending for retry.

If History receives an event before Game archive data is ready, it returns
`409 ARCHIVE_NOT_READY`. This is retryable from Game's perspective.

## Non-Goals

This contract does not provide:

- exactly-once delivery,
- broker semantics,
- replay APIs,
- pagination or search,
- authentication or authorization,
- tracing/metrics guarantees,
- production readiness checks,
- direct access to active Game state.

Those are intentionally outside this local/dev extraction slice.
