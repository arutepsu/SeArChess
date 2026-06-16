# Tournament Outbox Publisher (Phase 11C)

## Overview

Phase 11C adds a publisher abstraction and background poller that reads pending outbox events
(written by Phase 11B) and marks them published after a successful publish attempt.

The current publisher is a logging-only implementation. A Kafka producer will be added behind the
same `OutboxPublisher` interface in a future phase without touching tournament job or analysis logic.

---

## Why this is a separate phase

Phase 11B already guarantees that lifecycle events are durably written to
`tournament_outbox_events` in the same database transaction as the job state change. The
publisher is therefore decoupled: it operates asynchronously, on already-committed rows, and
its failures cannot affect job state.

This separation means:
- Tournament job processing is never blocked by publisher latency.
- A publish failure marks the outbox row `failed` but leaves the job row untouched.
- A future Kafka publisher can be dropped in by changing `TOURNAMENT_OUTBOX_PUBLISHER_TYPE`
  and wiring the new implementation — no changes to `TournamentJobService` or the repository
  write path.

---

## Components

### `OutboxPublisher` trait

```scala
trait OutboxPublisher:
  def publish(event: OutboxEvent): IO[Unit]
```

Implementations:

| Type                   | Env value  | Behavior                                                             |
|------------------------|------------|----------------------------------------------------------------------|
| `LoggingOutboxPublisher` | `logging` | Logs `outbox_event_published` via `StructuredLog`. Default.         |
| `NoOpOutboxPublisher`    | `noop`    | Discards the event silently. For load tests.                        |
| `KafkaOutboxPublisher`   | `kafka`   | Publishes to `searchess.tournament.lifecycle.v1` via Kafka producer. |

`KafkaOutboxPublisher` uses `KafkaEnvelopeBuilder` to wrap each `OutboxEvent` in the Phase 11D
envelope format and delegates to `KafkaRecordSender` (backed by `JvmKafkaRecordSender` in
production) to send the record. The producer is closed on service shutdown.

### `OutboxEventRepository` trait

```scala
trait OutboxEventRepository:
  def fetchPending(limit: Int): IO[List[OutboxEvent]]
  def markPublished(eventId: String): IO[Unit]
  def markFailed(eventId: String, error: String): IO[Unit]
  def countPending(): IO[Int]
  def countFailed(): IO[Int]
```

Implementations:
- `SlickOutboxEventRepository` — reads/writes `tournament_outbox_events` via plain SQL; shares the same `Database` instance as `SlickTournamentJobRepository`.
- `InMemoryOutboxEventRepository` — in-memory `Ref`-backed implementation for unit tests.

### `OutboxPoller`

```scala
final class OutboxPoller(
    repo: OutboxEventRepository,
    publisher: OutboxPublisher,
    pollInterval: FiniteDuration,
    batchSize: Int
)
```

Behavior per poll cycle:
1. `repo.fetchPending(batchSize)` — ordered by `created_at ASC`.
2. For each event:
   - Call `publisher.publish(event)`.
   - On success → `repo.markPublished(eventId)`.
   - On failure → log error + `repo.markFailed(eventId, errorMessage)`.
3. Processing continues to the next event even if one fails.
4. Sleep `pollInterval` seconds, then repeat.

The poller runs as a `cats-effect` background fiber and is cancelled on service shutdown.

---

## Lifecycle status transitions (from Phase 11B)

```
pending  →  published   (OutboxPoller.publishOne succeeds)
pending  →  failed      (OutboxPoller.publishOne raises an exception)
```

`attempt_count` is incremented each time `markFailed` is called.
`last_error` is overwritten on each failure.

Phase 11D will add retry for `failed` rows (reset to `pending` after manual or automatic re-queue).

---

## Configuration

| Env var                              | Default   | Notes                                                    |
|--------------------------------------|-----------|----------------------------------------------------------|
| `TOURNAMENT_OUTBOX_PUBLISHER_ENABLED` | `false`  | Must be `true` to start the poller                       |
| `TOURNAMENT_OUTBOX_PUBLISHER_TYPE`   | `logging` | `logging`, `noop`, or `kafka`                            |
| `TOURNAMENT_OUTBOX_POLL_INTERVAL_SECONDS` | `5`  | Seconds between poll cycles                              |
| `TOURNAMENT_OUTBOX_BATCH_SIZE`       | `50`      | Max events fetched per cycle                             |
| `TOURNAMENT_OUTBOX_MAX_ATTEMPTS`     | `10`      | Stored in config; used by future retry logic             |
| `TOURNAMENT_KAFKA_BOOTSTRAP_SERVERS` | —         | Required when `PUBLISHER_TYPE=kafka`                     |
| `TOURNAMENT_KAFKA_TOPIC`             | `searchess.tournament.lifecycle.v1` | Kafka topic name         |
| `TOURNAMENT_KAFKA_CLIENT_ID`         | `searchess-tournament-service` | Kafka producer client ID      |
| `TOURNAMENT_KAFKA_ACKS`              | `all`     | Kafka producer acks setting (`all`, `1`, `0`)            |

The poller only starts when **both** conditions are true:
- `TOURNAMENT_JOB_STORE=postgres`
- `TOURNAMENT_OUTBOX_PUBLISHER_ENABLED=true`

If `TOURNAMENT_OUTBOX_PUBLISHER_ENABLED=true` but `TOURNAMENT_JOB_STORE=memory`, the service
logs a warning and skips the poller (no in-memory outbox is wired for the background poller).

---

## Startup wiring

```
TournamentServiceWiring.start()
  └── buildRepositories(config)
        ├── SlickTournamentJobRepository(db, schema)   ← writes outbox events
        └── SlickOutboxEventRepository(db, schema)     ← reads + marks outbox events (same db pool)
  └── buildOutboxPoller(config, outboxRepoOpt)
        ├── config.outboxPublisherEnabled=false  → None (poller not started)
        └── config.outboxPublisherEnabled=true
              ├── publisherType=logging → LoggingOutboxPublisher
              └── publisherType=noop   → NoOpOutboxPublisher
              OutboxPoller(repo, publisher, interval, batchSize).start().start → FiberIO[Nothing]
```

The fiber is cancelled in `TournamentServiceRuntime.shutdown()` before the HTTP server and worker
fibers are stopped.

---

## Failure semantics

| Scenario                              | Outcome                                         |
|---------------------------------------|-------------------------------------------------|
| Publisher raises exception            | `status=failed`, `attempt_count++`, `last_error` set; next event processed normally |
| Poller crashes (uncaught error)       | Fiber terminates; service continues; outbox events stay `pending` until service restart |
| DB unreachable during `fetchPending`  | IO error propagates; poller fiber terminates; job service unaffected |
| Job state write fails                 | No outbox row written (Phase 11B atomicity); publisher has nothing to publish |

Tournament job and analysis state are **never** affected by publish failures.

---

## What is NOT in Phase 11C

- No Kafka producer
- No Kafka configuration keys
- No retry of `failed` rows (manual or automatic)
- No dead-letter queue
- No `GET /api/tournaments/outbox/stats` endpoint
- No `POST /api/tournaments/outbox/retry-failed` endpoint

These belong in Phase 11D.

---

## Manual verification

```
TOURNAMENT_JOB_STORE=postgres
TOURNAMENT_POSTGRES_URL=jdbc:postgresql://localhost:5432/searchess
TOURNAMENT_POSTGRES_USER=searchess
TOURNAMENT_POSTGRES_PASSWORD=searchess
TOURNAMENT_OUTBOX_PUBLISHER_ENABLED=true
TOURNAMENT_OUTBOX_PUBLISHER_TYPE=logging
TOURNAMENT_OUTBOX_POLL_INTERVAL_SECONDS=3
```

1. Start tournament-service with the vars above.
2. Create a small tournament (`POST /api/tournaments`).
3. Wait for it to complete.
4. Query the outbox table:

```sql
SELECT event_type, status, attempt_count, published_at
FROM public.tournament_outbox_events
ORDER BY created_at;
```

5. Confirm that `TournamentJobCreated`, `TournamentJobStarted`, `TournamentGameCompleted*N`,
   `TournamentJobSucceeded` all have `status='published'` and `published_at` set.
6. Check service logs for `outbox_event_published` JSON lines.

To test the disabled path:
1. Start without `TOURNAMENT_OUTBOX_PUBLISHER_ENABLED=true`.
2. Run a tournament.
3. Confirm all outbox rows stay at `status='pending'`.
4. Restart with publisher enabled — rows should move to `published` on the next poll.

---

## Relationship to previous phases

| Phase  | What it added                                                             |
|--------|---------------------------------------------------------------------------|
| 11A    | PostgreSQL persistence for `tournament_jobs` and `analysis_jobs`          |
| 11B    | `tournament_outbox_events` table; atomic outbox writes with job state      |
| **11C**| `OutboxPublisher` trait + `OutboxPoller` background fiber; logging publisher |
| 11D    | Kafka publisher behind `OutboxPublisher`; retry for `failed` rows         |
