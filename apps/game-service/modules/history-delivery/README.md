# game-history-delivery

Scope: Game-owned delivery infrastructure for terminal Game events consumed by
History Service.

This module owns:

- `HistoryEventOutbox`
- `SqliteHistoryEventOutbox`
- `HistoryOutboxForwarder`
- `HistoryHttpEventPublisher`
- `DurableHistoryEventPublisher`

It is intentionally local/dev delivery infrastructure, not a broker framework.
It uses the event JSON serializer from `game-event-contract` and persists /
forwards terminal events to History.

The module does not define the wire schema; it only stores and delivers payloads
that already follow the Game event contract.

## Reliability model

SQLite deployments use `history_event_outbox` as the delivery source of truth.
Rows with `delivered_at IS NULL` are pending. A pending row with `attempts = 0`
has not yet been tried; a pending row with `attempts > 0` is retrying.
`last_attempted_at` records the most recent delivery attempt and `last_error`
records the most recent failed attempt. Delivered rows keep their history and
are excluded from future forwarder batches.

`HistoryOutboxForwarder` automatically polls pending rows while the Game Service
is running. Each batch calls `markAttempted` before sending HTTP to History. A
2xx response marks the row delivered; any send failure records `last_error` and
leaves the row pending for the next poll or process restart. There is no
permanent-failure/dead-letter state yet.

Delivery is at-least-once. If Game crashes after History accepts an event but
before the outbox row is marked delivered, the event may be retried. History
therefore treats archive ingestion as idempotent, keyed by `gameId`.

In-memory Game persistence has no durable outbox and remains best-effort HTTP
delivery only.
