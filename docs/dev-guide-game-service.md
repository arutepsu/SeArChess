# Game Service Local Dev Guide

This guide documents local/dev operational surfaces for the standalone Game
Service. It is not a production admin API contract.

## History Outbox Inspection

When Game Service runs with:

| Variable | Value |
|---|---|
| `PERSISTENCE_MODE` | `sqlite` |
| `HISTORY_FORWARDING_ENABLED` | `true` |

terminal History-facing events are written to the SQLite
`history_event_outbox` table and drained by the background History forwarder.

The Game Service exposes a small read-only inspection surface:

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/ops/history-outbox` | summary counts and pending timestamps |
| `GET` | `/ops/history-outbox/pending` | pending/retrying rows, oldest first |
| `GET` | `/ops/history-outbox/{id}` | one row, including payload JSON |

These endpoints are for local/dev debugging. They do not retry, replay, delete,
or mutate outbox rows.

### Summary

```bash
curl -s http://127.0.0.1:8080/ops/history-outbox
```

Example:

```json
{
  "totalCount": 3,
  "pendingCount": 1,
  "deliveredCount": 2,
  "retryingCount": 1,
  "oldestPendingAt": "2026-04-20T12:00:00Z",
  "newestPendingAt": "2026-04-20T12:00:00Z",
  "pendingByType": {
    "game.session.cancelled.v1": 1
  }
}
```

### Pending Rows

```bash
curl -s http://127.0.0.1:8080/ops/history-outbox/pending
```

Rows expose only fields that exist in storage:

- `id`
- `eventType`
- `sessionId`
- `gameId`
- `createdAt`
- `attempts`
- `status`: `pending`, `retrying`, or `delivered`
- `pending`
- `lastError`
- `deliveredAt`

There is no `lastAttemptAt` field today because the SQLite outbox schema does
not store one.

### Row Detail

```bash
curl -s http://127.0.0.1:8080/ops/history-outbox/1
```

The detail response includes `payloadJson`, parsed as JSON, so an engineer can
see the exact terminal event payload that will be POSTed to History.

## Non-Goals

This surface intentionally does not provide:

- replay controls
- retry-now controls
- delete controls
- dead-letter handling
- broker offsets
- exactly-once delivery guarantees
- production auth or audit controls

It is just enough read-only visibility to understand whether the local/dev
Game -> History outbox is healthy or stuck.
