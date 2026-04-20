# Game to History Outbox

Status: local/dev durability bridge  
Scope: terminal Game events consumed by History  
Owner: Game Service

## Purpose

The Game Service now has a small outbox-style bridge for automatic delivery of
terminal Game events to History. This replaces the important local/dev weakness
of the earlier best-effort HTTP publisher: if History is down when a terminal
event is emitted, the event can remain in SQLite and be retried later.

This is intentionally not Kafka, not a broker, and not a general event
platform.

## Events Covered

Only terminal History-facing events are written to the outbox:

- `GameFinished`
- `GameResigned`
- `SessionCancelled`

Other `AppEvent` values remain in-process only and are ignored by this bridge.

## Runtime Shape

When `HISTORY_FORWARDING_ENABLED=true` and `PERSISTENCE_MODE=sqlite`:

1. A terminal game command (checkmate, resign, cancel) is received.
2. The application service serialises the terminal event to JSON using
   `AppEventSerializer`.
3. The serialised payload is passed to the persistence store alongside the
   state write.
4. `SqliteSessionGameStore.saveTerminal` (for GameFinished / GameResigned) or
   `SqliteSessionRepository.saveCancelWithOutbox` (for SessionCancelled)
   commits the session/game-state row **and** the outbox row in a **single JDBC
   transaction**.
5. `HistoryOutboxForwarder` runs in the Game Service process.
6. The forwarder reads undelivered rows, POSTs each payload to
   `History /events/game`, and marks the row delivered on HTTP 2xx.
7. Failed deliveries increment `attempts`, store `last_error`, and remain
   pending.

History still owns archive materialisation. The outbox does not store History
records and does not read Game tables. It stores only the event JSON trigger
payload that History already accepts.

## SQLite Table

The Game Service SQLite file contains a separate outbox table:

```sql
history_event_outbox (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  event_type   TEXT NOT NULL,
  session_id   TEXT NOT NULL,
  game_id      TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  created_at   TEXT NOT NULL,
  attempts     INTEGER NOT NULL DEFAULT 0,
  last_error   TEXT,
  delivered_at TEXT
)
```

Rows with `delivered_at IS NULL` are pending.

The table is created by `SqliteSchema.createTables` (run on every startup via
`PersistenceAssembly`) and also by `SqliteHistoryEventOutbox.initialize()` so
the class continues to work in isolation without the full schema.

## What Is Durable Now

In SQLite mode, once a terminal event is written, History delivery is
retryable across:

- temporary History outage
- failed HTTP POST
- Game Service restart

After restart, the forwarder reads pending SQLite rows and retries delivery.

## Transactional Guarantees

### What is now atomic

In SQLite mode, for each terminal operation the following writes land in a
**single JDBC transaction**:

| Operation      | Transaction scope |
|----------------|-------------------|
| `GameFinished` (checkmate / draw via `submitMove`) | `sessions` row + `game_states` row + `history_event_outbox` row |
| `GameResigned` | `sessions` row + `game_states` row + `history_event_outbox` row |
| `SessionCancelled` | `sessions` row + `history_event_outbox` row |

Either all writes in the group commit, or none of them do. There is no scenario
in SQLite mode where the game/session state is committed but the corresponding
outbox row is missing.

Implementation: `SqliteSessionGameStore.saveTerminal` and
`SqliteSessionRepository.saveCancelWithOutbox` use `SqliteDataSource.withTransaction`
which sets `autoCommit=false`, commits on `Right`, and rolls back on `Left` or
`SQLException`. The outbox INSERT is performed by `OutboxInsert` using the same
`java.sql.Connection` object, ensuring it participates in the same transaction.

### What is still NOT guaranteed

- **Delivery is at-least-once, not exactly-once.** History ingestion must
  remain idempotent / upsert-based. A crash between `markDelivered` and the
  forwarder loop re-starting can cause a re-delivery.
- **In-memory mode has no outbox.** `NoOpTerminalEventJsonSerializer` produces
  no payloads so the outbox is never written. If `HISTORY_FORWARDING_ENABLED`
  is true with `PERSISTENCE_MODE=inmemory`, the service falls back to best-effort
  HTTP delivery and logs that it is not durable.
- **Non-terminal events are not in scope.** `MoveApplied`, `SessionCreated`,
  and internal events are never written to the outbox.
- **There is no retry backoff, dead-letter queue, admin replay endpoint, or
  broker offset management.**

### Why this is sufficient for this stage

The transactional guarantee closes the last meaningful consistency gap between
Game state and History trigger:

- Game state committed → outbox row exists → History will eventually learn.
- Game state rolled back → no outbox row → History never receives a phantom event.

The remaining at-least-once caveat is already handled by History's upsert
ingestion. No new infrastructure is required.

## Why Not A Broker Yet

The current extraction goal is to prove a real service boundary with the least
new infrastructure:

- Game remains authoritative for active play.
- History remains downstream and owns archive storage.
- The terminal event JSON contract remains the trigger.
- SQLite is already mounted and preserved in local/dev.

This gives practical retry-after-outage behavior without introducing Kafka,
RabbitMQ, schema registry, consumer groups, or operational work that would hide
the smaller ownership seam we are trying to validate.
