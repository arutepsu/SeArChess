# Tournament Kafka Event Contract (Phase 11D)

## Overview

This document defines the Kafka topic names, message envelope, event type catalogue, and
consumer rules for tournament lifecycle events produced by `tournament-service`.

Phase 11B writes durable outbox rows. Phase 11C polls them and publishes through the
`OutboxPublisher` interface. Phase 11D will wire a `KafkaOutboxPublisher` behind that interface
to deliver events to Kafka. This document is the contract that both the producer and consumers
must honour.

---

## Topic names

### Primary topic (Phase 11D)

| Topic                             | Partition key | Contents                                              |
|-----------------------------------|---------------|-------------------------------------------------------|
| `searchess.tournament.lifecycle.v1` | `aggregateId` | All tournament job and analysis job lifecycle events  |

All ten Phase 11B event types land in this single topic. Separating tournament events from
analysis events would require consumers to subscribe to multiple topics for full lifecycle
visibility; a unified topic simplifies correlation.

### Optional future topics

These topics are **not created in Phase 11D** and are listed here for planning only.

| Topic                           | Contents                                        |
|---------------------------------|-------------------------------------------------|
| `searchess.game.events.v1`      | Per-move or per-game JSONL game event stream    |
| `searchess.analytics.lifecycle.v1` | Spark analytics run completion events        |

Game move data is large and belongs in a dedicated high-throughput topic. Analytics result tables
live in PostgreSQL read models and are not republished to Kafka in the current design.

---

## Message key

Use `aggregateId` from the outbox row as the Kafka message key.

| Outbox `aggregate_type` | `aggregateId` value              | Kafka message key  |
|-------------------------|----------------------------------|--------------------|
| `tournament_job`        | `jobId` (UUID)                   | `jobId`            |
| `analysis_job`          | `analysisJobId` (UUID)           | `analysisJobId`    |

Using `aggregateId` as the key causes Kafka to route all events for the same entity to the same
partition, preserving per-entity ordering. Consumers that reconstruct state (e.g. a job status
read model) can rely on ordered delivery within a partition for a single `aggregateId`.

---

## Message value envelope

Every Kafka message produced to `searchess.tournament.lifecycle.v1` uses this JSON envelope:

```json
{
  "eventId":       "550e8400-e29b-41d4-a716-446655440001",
  "eventType":     "TournamentJobCreated",
  "aggregateType": "tournament_job",
  "aggregateId":   "550e8400-e29b-41d4-a716-446655440000",
  "occurredAt":    "2026-06-15T21:00:00.000Z",
  "schemaVersion": 1,
  "payload":       { ... }
}
```

### Field mapping from outbox row

| Outbox column     | Envelope field   | Notes                                              |
|-------------------|------------------|----------------------------------------------------|
| `event_id`        | `eventId`        | UUID; globally unique; use for deduplication       |
| `event_type`      | `eventType`      | Stable string; see catalogue below                 |
| `aggregate_type`  | `aggregateType`  | `"tournament_job"` or `"analysis_job"`             |
| `aggregate_id`    | `aggregateId`    | Same value used as the Kafka message key           |
| `created_at`      | `occurredAt`     | ISO-8601 instant; when the state change occurred   |
| `payload_json`    | `payload`        | Embedded as a JSON object, not a nested string     |
| _(derived)_       | `schemaVersion`  | Hard-coded `1`; increment on breaking changes      |

`payload_json` is stored in the outbox as a compact JSON string.
`KafkaOutboxPublisher` must parse it and embed it as a structured object inside the envelope
(not as a JSON-encoded string within the JSON value).

---

## Event type catalogue

All payloads are defined in [tournament-outbox-events.md](tournament-outbox-events.md).
The table below maps each event type to its aggregate type and the service method that emits it.

### Tournament lifecycle

| `eventType`               | `aggregateType`   | Emitted in                  |
|---------------------------|-------------------|-----------------------------|
| `TournamentJobCreated`    | `tournament_job`  | `createJob`                 |
| `TournamentJobStarted`    | `tournament_job`  | `markRunning`               |
| `TournamentGameCompleted` | `tournament_job`  | `incrementCompletedGames`   |
| `TournamentJobSucceeded`  | `tournament_job`  | `markSucceeded`             |
| `TournamentJobFailed`     | `tournament_job`  | `markFailed`                |
| `TournamentJobCancelled`  | `tournament_job`  | `cancelJob` / `finalizeCancelled` |

### Analysis lifecycle

| `eventType`               | `aggregateType`  | Emitted in              |
|---------------------------|------------------|-------------------------|
| `AnalysisJobQueued`       | `analysis_job`   | `queueAnalysis`         |
| `AnalysisJobStarted`      | `analysis_job`   | `markAnalysisRunning`   |
| `AnalysisJobSucceeded`    | `analysis_job`   | `markAnalysisSucceeded` |
| `AnalysisJobFailed`       | `analysis_job`   | `markAnalysisFailed`    |

### Full envelope examples

#### `TournamentJobCreated`

```json
{
  "eventId":       "7e3a4f20-...",
  "eventType":     "TournamentJobCreated",
  "aggregateType": "tournament_job",
  "aggregateId":   "550e8400-...",
  "occurredAt":    "2026-06-15T21:00:00Z",
  "schemaVersion": 1,
  "payload": {
    "jobId":          "550e8400-...",
    "name":           null,
    "selectedBotIds": ["random-bot", "capture-first"],
    "mode":           "double-round-robin",
    "repetitions":    1,
    "maxPly":         80,
    "seed":           null,
    "plannedGames":   2,
    "createdAt":      "2026-06-15T21:00:00Z"
  }
}
```

#### `TournamentGameCompleted`

```json
{
  "eventId":       "9c1b2d30-...",
  "eventType":     "TournamentGameCompleted",
  "aggregateType": "tournament_job",
  "aggregateId":   "550e8400-...",
  "occurredAt":    "2026-06-15T21:00:05Z",
  "schemaVersion": 1,
  "payload": {
    "jobId":          "550e8400-...",
    "completedGames": 1,
    "plannedGames":   2,
    "occurredAt":     "2026-06-15T21:00:05Z"
  }
}
```

#### `AnalysisJobSucceeded`

```json
{
  "eventId":       "bb4c5e60-...",
  "eventType":     "AnalysisJobSucceeded",
  "aggregateType": "analysis_job",
  "aggregateId":   "aaa-111-...",
  "occurredAt":    "2026-06-15T21:01:30Z",
  "schemaVersion": 1,
  "payload": {
    "analysisJobId":   "aaa-111-...",
    "tournamentJobId": "550e8400-...",
    "analyticsRunId":  "run-abc-123",
    "outputPath":      "target/spark-analytics/tournament-jobs/550e8400-...",
    "finishedAt":      "2026-06-15T21:01:30Z"
  }
}
```

---

## Delivery semantics

| Property           | Value / guarantee                                                         |
|--------------------|---------------------------------------------------------------------------|
| Delivery guarantee | **At-least-once.** The outbox poller retries on transient failures.       |
| Ordering           | Per-`aggregateId` within a partition. No global ordering across tournaments. |
| Duplicates         | Possible on poller restart after a partial batch. Consumers must be idempotent. |
| Lag                | Events appear in Kafka after the next poll cycle (default: 5 seconds). Not real-time. |

`status='published'` in the outbox means the Kafka producer accepted the record (`RecordMetadata`
returned without error). It does **not** mean downstream consumers processed the event.

---

## Compatibility rules (v1)

### What is allowed in v1

- Add new **optional** fields to any payload. Consumers following the ignore-unknown rule are unaffected.
- Add new `eventType` values. Consumers that filter by `eventType` will ignore them.
- Add new `aggregateType` values.

### What is not allowed in v1

- Remove existing fields.
- Rename existing fields.
- Change the type or semantics of existing fields.
- Make previously optional fields required.

### When v2 is needed

Any breaking change requires either:
- A new topic `searchess.tournament.lifecycle.v2`, OR
- A `schemaVersion` increment to `2` in the envelope, with consumers branching on the version field.

`eventType` name strings are **stable identifiers**. Renaming an event type (e.g.
`TournamentJobCancelled` → `TournamentJobAborted`) is a breaking change.

---

## Consumer guidance

### Deduplication

Deduplicate by `eventId`. The same `eventId` will never be produced for two different facts;
receiving the same `eventId` twice means the producer retried after a partial commit.

### Ordering

Process events per `aggregateId` in `offset` order within a partition. If your consumer needs
to reconstruct job state, process all events for one `jobId` in offset order. Do not assume
ordering across different `jobId` values, even within the same partition.

### Unknown fields and event types

Ignore unknown fields in `payload`. Ignore unknown `eventType` values (log and skip). This
ensures forward compatibility when new event types are added.

### Cross-aggregate correlation

To correlate analysis events with the originating tournament:

1. Consume `AnalysisJobQueued` — the `payload.tournamentJobId` links to the tournament.
2. Subsequent analysis events (`AnalysisJobStarted`, `AnalysisJobSucceeded`, `AnalysisJobFailed`)
   share the same `aggregateId` (`analysisJobId`) for the same analysis attempt.
3. On retry, a new `analysisJobId` is generated. A fresh `AnalysisJobQueued` event will appear
   with the new `analysisJobId` but the same `tournamentJobId`.

### Suggested consumer pattern

```
consume(event):
  if already_seen(event.eventId): return   # idempotency check
  mark_seen(event.eventId)
  dispatch on event.eventType:
    "TournamentJobCreated"    → upsert job in read model (status=queued)
    "TournamentJobStarted"    → update job status=running
    "TournamentGameCompleted" → increment counter
    "TournamentJobSucceeded"  → update job status=succeeded
    "TournamentJobFailed"     → update job status=failed, store errorMessage
    "TournamentJobCancelled"  → update job status=cancelled
    "AnalysisJobQueued"       → upsert analysis row (status=queued)
    "AnalysisJobStarted"      → update analysis status=running
    "AnalysisJobSucceeded"    → update analysis status=succeeded, store runId
    "AnalysisJobFailed"       → update analysis status=failed, store error
    unknown                   → log and skip
```

---

## Non-goals for this topic

- **No game move stream.** Individual move sequences belong in a separate high-throughput topic
  (e.g. `searchess.game.events.v1`) with a different retention and schema strategy.
- **No Spark analytics results.** Leaderboards and move statistics are PostgreSQL read-model
  tables written by Spark. They are not re-published to Kafka in the current design.
- **No large payloads.** Outbox payloads are compact lifecycle facts (job IDs, timestamps, status
  strings). File paths are included but file contents are not.
- **No direct UI dependency.** The web UI reads from `tournament-service` HTTP endpoints, not
  from Kafka. Kafka consumers are backend read-model builders.

---

## Implementation: KafkaOutboxPublisher (Phase 11E)

`KafkaOutboxPublisher` implements the `OutboxPublisher` trait introduced in Phase 11C:

```scala
final class KafkaOutboxPublisher(sender: KafkaRecordSender, topic: String) extends OutboxPublisher:
  def publish(event: OutboxEvent): IO[Unit] =
    IO.fromEither(KafkaEnvelopeBuilder.build(event).left.map(RuntimeException(_)))
      .flatMap { envelope =>
        sender.send(topic, event.aggregateId, envelope)
      }
```

`KafkaEnvelopeBuilder.build` parses `event.payloadJson` via ujson and embeds it as a
structured object inside the envelope (not as an escaped JSON string). If `payloadJson` is
invalid JSON, `build` returns `Left(...)` and the publish fails with an IO error; the
`OutboxPoller` then marks the row `failed`.

`KafkaRecordSender` is a thin trait over `KafkaProducer`, extracted for testability:

```scala
trait KafkaRecordSender:
  def send(topic: String, key: String, value: String): IO[Unit]
  def close(): IO[Unit]
```

`JvmKafkaRecordSender` wraps `org.apache.kafka.clients.producer.KafkaProducer`. The producer
is closed via `TournamentServiceRuntime.closeResources` on service shutdown.

To enable in production:

```
TOURNAMENT_OUTBOX_PUBLISHER_ENABLED=true
TOURNAMENT_JOB_STORE=postgres
TOURNAMENT_OUTBOX_PUBLISHER_TYPE=kafka
TOURNAMENT_KAFKA_BOOTSTRAP_SERVERS=broker1:9092,broker2:9092
TOURNAMENT_KAFKA_TOPIC=searchess.tournament.lifecycle.v1
TOURNAMENT_KAFKA_CLIENT_ID=searchess-tournament-service
TOURNAMENT_KAFKA_ACKS=all
```

`TOURNAMENT_KAFKA_BOOTSTRAP_SERVERS` is required when `TOURNAMENT_OUTBOX_PUBLISHER_TYPE=kafka`;
config validation rejects the combination without it.

---

## Relationship to other phases

| Phase  | What it added                                                              |
|--------|----------------------------------------------------------------------------|
| 11A    | PostgreSQL persistence for `tournament_jobs` and `analysis_jobs`           |
| 11B    | `tournament_outbox_events` table; atomic outbox inserts with job state     |
| 11C    | `OutboxPublisher` trait + `OutboxPoller` background fiber; logging publisher |
| **11D**| Kafka topic contract, envelope format, consumer rules (this document)      |
| **11E**| `KafkaOutboxPublisher` + `KafkaEnvelopeBuilder` + `KafkaRecordSender`; Kafka config wired |
