# Tournament Outbox Events (Phase 11B)

## Overview

Phase 11B adds a **transactional outbox table** (`tournament_outbox_events`) to
`tournament-service`. Every tournament and analysis lifecycle state change now atomically
writes an outbox row in the same database transaction as the state change itself. A future
Kafka publisher (Phase 11C/11D) will read `pending` rows and publish them to Kafka topics
without any risk of lost or duplicated events.

No Kafka dependency is added in this phase. No messages are published. The outbox is the
durability boundary between the tournament service and the event bus.

---

## Why the outbox pattern is needed before Kafka

Without a transactional outbox, there are two common failure modes:

1. **State change committed, event not sent** — the service crashes after writing to
   `tournament_jobs` but before calling the Kafka producer. The event is lost permanently.
2. **Event sent, state change rolled back** — the Kafka producer is called first; if the
   DB transaction rolls back afterwards, the event was published for a job that never
   existed in that state.

The outbox pattern eliminates both risks:

- The state change SQL and the `INSERT INTO tournament_outbox_events` happen inside a
  single `transactionally` Slick `DBIO`. Both commit or neither commits.
- The Kafka publisher reads only `status = 'pending'` rows that already represent
  committed state.
- If the publisher crashes mid-publish, it retries: at-least-once delivery with
  idempotent consumers handling duplicates.

---

## Table schema

```sql
CREATE TABLE IF NOT EXISTS <schema>.tournament_outbox_events (
  event_id       TEXT PRIMARY KEY,
  aggregate_type TEXT NOT NULL,            -- "tournament_job" | "analysis_job"
  aggregate_id   TEXT NOT NULL,            -- jobId or analysisJobId (UUID)
  event_type     TEXT NOT NULL,            -- e.g. "TournamentJobCreated"
  payload_json   TEXT NOT NULL,            -- compact JSON, see contracts below
  status         TEXT NOT NULL DEFAULT 'pending',  -- pending | published | failed
  created_at     TEXT NOT NULL,            -- ISO-8601 instant
  published_at   TEXT,                     -- set by publisher on success
  last_error     TEXT,                     -- set by publisher on failure
  attempt_count  INT  NOT NULL DEFAULT 0   -- incremented on each publish attempt
);

CREATE INDEX IF NOT EXISTS idx_outbox_status_created
  ON tournament_outbox_events (status, created_at);

CREATE INDEX IF NOT EXISTS idx_outbox_aggregate
  ON tournament_outbox_events (aggregate_type, aggregate_id);

CREATE INDEX IF NOT EXISTS idx_outbox_event_type
  ON tournament_outbox_events (event_type);
```

The table is created via `CREATE TABLE IF NOT EXISTS` in `SlickTournamentJobRepository.initSchema()`.
No Flyway or Liquibase is used.

---

## Event types and payload contracts

All payloads are compact JSON strings. Fields are stable — adding new fields is allowed;
removing or renaming fields requires a versioning strategy (future concern).

### Tournament lifecycle

#### `TournamentJobCreated`
Emitted in `createJob`.

```json
{
  "jobId": "550e8400-...",
  "name": "Heuristic smoke",
  "selectedBotIds": ["random-bot", "capture-first"],
  "mode": "double-round-robin",
  "repetitions": 1,
  "maxPly": 80,
  "seed": null,
  "plannedGames": 2,
  "createdAt": "2026-06-15T20:00:00Z"
}
```

#### `TournamentJobStarted`
Emitted in `markRunning` (only when the UPDATE actually transitions the row from `queued`).

```json
{"jobId": "550e8400-...", "startedAt": "2026-06-15T20:00:01Z"}
```

#### `TournamentGameCompleted`
Emitted in `incrementCompletedGames` after the counter is incremented.

```json
{
  "jobId": "550e8400-...",
  "completedGames": 1,
  "plannedGames": 2,
  "occurredAt": "2026-06-15T20:00:05Z"
}
```

#### `TournamentJobSucceeded`
Emitted in `markSucceeded`.

```json
{
  "jobId": "550e8400-...",
  "outputPath": "target/arena/tournament-jobs/<jobId>/game-events.jsonl",
  "finishedAt": "2026-06-15T20:00:10Z"
}
```

#### `TournamentJobFailed`
Emitted in `markFailed`.

```json
{
  "jobId": "550e8400-...",
  "errorMessage": "Bot threw exception: ...",
  "finishedAt": "2026-06-15T20:00:10Z"
}
```

#### `TournamentJobCancelled`
Emitted in **two places**:
- `cancelJob` — when a **Queued** job is cancelled (HTTP cancel while job is waiting). `finishedAt` is set immediately.
- `finalizeCancelled` — when a **Running** job's worker detects the cancellation and sets the definitive `finishedAt`.

Each scenario emits exactly one `TournamentJobCancelled` event.

```json
{"jobId": "550e8400-...", "finishedAt": "2026-06-15T20:00:06Z"}
```

### Analysis lifecycle

Each analysis attempt gets a fresh `analysisJobId` UUID. On retry (re-calling
`POST /api/tournaments/:jobId/analyze` after failure), the `analysis_jobs` row is reset
and `analysis_job_id` is replaced with a new UUID, so each attempt has its own correlated
outbox events.

#### `AnalysisJobQueued`
Emitted in `queueAnalysis`.

```json
{
  "analysisJobId": "aaa-...",
  "tournamentJobId": "550e8400-...",
  "inputPath": "target/arena/tournament-jobs/<jobId>/game-events.jsonl",
  "outputPath": "target/spark-analytics/tournament-jobs/<jobId>",
  "createdAt": "2026-06-15T20:00:15Z"
}
```

#### `AnalysisJobStarted`
Emitted in `markAnalysisRunning`.

```json
{
  "analysisJobId": "aaa-...",
  "tournamentJobId": "550e8400-...",
  "startedAt": "2026-06-15T20:00:16Z"
}
```

#### `AnalysisJobSucceeded`
Emitted in `markAnalysisSucceeded`.

```json
{
  "analysisJobId": "aaa-...",
  "tournamentJobId": "550e8400-...",
  "analyticsRunId": "run-abc-123",
  "outputPath": "target/spark-analytics/tournament-jobs/<jobId>",
  "finishedAt": "2026-06-15T20:01:30Z"
}
```

#### `AnalysisJobFailed`
Emitted in `markAnalysisFailed`.

```json
{
  "analysisJobId": "aaa-...",
  "tournamentJobId": "550e8400-...",
  "errorMessage": "OOM in Spark driver",
  "finishedAt": "2026-06-15T20:01:30Z"
}
```

---

## Transaction boundary

In `SlickTournamentJobRepository`, every write method runs a Slick `DBIO` wrapped in
`.transactionally`. The pattern is:

```
BEGIN
  UPDATE tournament_jobs SET status = '...', ... WHERE job_id = ?
  INSERT INTO tournament_outbox_events (...) VALUES (...)
COMMIT
```

If either statement fails, both roll back. The outbox row is only visible to the future
publisher after the state change itself is durable.

For `InMemoryTournamentJobRepository`, outbox events are appended to an in-memory
`Ref[IO, Vector[OutboxEvent]]`. There is no real transaction (the implementation is for
local dev and unit tests), but the logical ordering is preserved.

---

## Outbox status lifecycle

```
pending  →  published   (Kafka publisher succeeds)
pending  →  failed      (Kafka publisher exhausted retries)
failed   →  pending     (manual reset or dead-letter re-queue)
```

`attempt_count` is incremented on each publish attempt. `last_error` and `published_at`
are set by the publisher. The tournament service itself never updates status beyond
`pending`.

---

## What is NOT stored in the outbox

- **Game event data** — full game events (moves, positions) are written to JSONL files and
  belong in a dedicated game-events Kafka topic (separate from lifecycle events).
- **Analytics results** — Spark output (leaderboards, move statistics) belongs in the
  analytics read-model tables, not in the outbox.
- **HTTP request/response bodies** — the outbox records state facts, not API traffic.

---

## Retry / replay semantics

The outbox publisher (Phase 11C/11D) is responsible for:
1. `SELECT ... WHERE status = 'pending' ORDER BY created_at LIMIT N` (batch poll)
2. Publish each event to its Kafka topic
3. `UPDATE tournament_outbox_events SET status = 'published', published_at = NOW() WHERE event_id = ?`
4. On failure: increment `attempt_count`, set `last_error`, optionally mark `failed` after max retries

Replaying from a checkpoint is supported by querying `WHERE status = 'pending' AND created_at > ?`.

---

## Relationship to `analysis_jobs.analysis_job_id` on retry

When `queueAnalysis` is called for a retry (the `analysis_jobs` row already exists for
the tournament), the `ON CONFLICT ... DO UPDATE` also resets `analysis_job_id` to the
newly generated UUID. This means:

- Each retry of an analysis has a distinct `analysisJobId` in the outbox.
- `AnalysisJobQueued`, `AnalysisJobStarted`, `AnalysisJobSucceeded`/`AnalysisJobFailed`
  for the same attempt all share the same `aggregateId` (the new UUID).
- A consumer can correlate a full analysis attempt lifecycle by `aggregateId`.

---

## Future Kafka publisher (Phase 11C / 11D)

Phase 11B defines the outbox contract. Phase 11C or 11D will add:

- A background fiber or sidecar process that polls `tournament_outbox_events` for
  `status = 'pending'` rows.
- A Kafka producer writing to dedicated topics:
  - `tournament.job.lifecycle` — TournamentJob* events
  - `tournament.analysis.lifecycle` — Analysis* events (or a combined topic)
- Transactional update of `status → published` after successful Kafka delivery.
- Dead-letter handling for events that exceed max retry count.
- No Kafka dependency in the tournament-service classpath until that phase.

---

## Configuration

No new configuration keys in Phase 11B. The outbox table uses the same PostgreSQL
connection and schema as `tournament_jobs` and `analysis_jobs` (configured via
`TOURNAMENT_POSTGRES_*` env vars).

---

## Manual verification

With `TOURNAMENT_JOB_STORE=postgres` and a running PostgreSQL:

```sql
-- Check pending events after creating a tournament
SELECT event_id, aggregate_type, event_type, status, created_at
FROM public.tournament_outbox_events
ORDER BY created_at DESC
LIMIT 20;

-- Check payload for a specific job
SELECT event_type, payload_json
FROM public.tournament_outbox_events
WHERE aggregate_id = '<your-job-id>'
ORDER BY created_at;
```
