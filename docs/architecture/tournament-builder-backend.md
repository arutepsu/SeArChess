# Tournament Builder Backend

Phase 10A adds `tournament-service`. Phase 11A adds PostgreSQL persistence for tournament and
analysis job state. See [tournament-job-persistence.md](tournament-job-persistence.md) for the
full schema, status lifecycle, and Kafka outbox plan.

## Purpose

The service provides the backend foundation for the Web UI tournament builder. A client can list
tournament-capable bots, create a tournament job, inspect job progress, cancel queued or running
jobs, and trigger Spark analytics.

It starts arena jobs, writes game events as JSONL, and optionally triggers Spark analytics. With
`TOURNAMENT_JOB_STORE=postgres`, job state survives restarts. It does not call analytics-service
directly, does not update the Web UI dashboard directly, and does not use Kafka yet (Phase 11B).

## Service

| Service | Default port | Module |
|---------|--------------|--------|
| tournament-service | 8085 | `apps/tournament-service` |

Configuration:

| Env var | Default | Description |
|---------|---------|-------------|
| `TOURNAMENT_HTTP_HOST` | `0.0.0.0` | HTTP bind host |
| `TOURNAMENT_HTTP_PORT` | `8085` | HTTP bind port |
| `TOURNAMENT_OUTPUT_BASE_PATH` | `target/arena/tournament-jobs` | Base directory for job output |
| `TOURNAMENT_MAX_PARALLEL_JOBS` | `1` | Positive value; Phase 10A runs one queue worker |
| `TOURNAMENT_ANALYTICS_ENABLED` | `true` | Enables explicit analytics execution requests |
| `TOURNAMENT_ANALYTICS_OUTPUT_BASE_PATH` | `target/spark-analytics/tournament-jobs` | Default Spark analytics output base |
| `TOURNAMENT_MAX_PARALLEL_ANALYTICS_JOBS` | `1` | Positive value; defaults to one analytics worker |
| `TOURNAMENT_ANALYTICS_SBT_COMMAND` | `cmd.exe /c sbt --client=false` on Windows, `sbt --client=false` elsewhere | Command prefix used by the process-backed Spark runner |
| `STOCKFISH_PATH` | unset | Enables Stockfish bot entries when it points to a file |
| `SEARCHESS_AI_BASE_URL` | unset | Enables `searchess-ai-v1` |
| `TOURNAMENT_JOB_STORE` | `memory` | `memory` (default) or `postgres` |
| `TOURNAMENT_POSTGRES_URL` | unset | Required when `TOURNAMENT_JOB_STORE=postgres` |
| `TOURNAMENT_POSTGRES_USER` | unset | Required when `TOURNAMENT_JOB_STORE=postgres` |
| `TOURNAMENT_POSTGRES_PASSWORD` | unset | Required when `TOURNAMENT_JOB_STORE=postgres` |
| `TOURNAMENT_POSTGRES_SCHEMA` | `public` | PostgreSQL schema for tournament tables |

Spark/PostgreSQL behavior is configured by the existing Spark environment variables, including `POSTGRES_WRITE_ENABLED`, `POSTGRES_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_SCHEMA`, `POSTGRES_WRITE_MODE`, `POSTGRES_STRICT_WRITE`, `SPARK_LAKE_WRITE_ENABLED`, `SPARK_LAKE_BASE_PATH`, and `SPARK_LAKE_WRITE_MODE`.

The default analytics SBT command disables the SBT client/server with `--client=false`. This avoids nested SBT server and named-pipe lock conflicts when `tournament-service` is launched from SBT and then starts a child Spark analytics SBT process, especially on Windows. If you override `TOURNAMENT_ANALYTICS_SBT_COMMAND`, include `--client=false` unless you have a specific reason not to.

## API

### `GET /health`

```json
{"status":"ok","service":"searchess-tournament-service"}
```

### `GET /api/tournaments/bots`

Returns known bots and availability metadata. Heuristic bots are always available. Stockfish bots require `STOCKFISH_PATH`. SearchessAI requires `SEARCHESS_AI_BASE_URL`.

### `POST /api/tournaments`

Creates a tournament job and queues it for the background worker.

```json
{
  "name": "Heuristic smoke",
  "botIds": ["random-bot", "capture-first"],
  "mode": "double-round-robin",
  "repetitions": 1,
  "maxPly": 80,
  "seed": 42
}
```

Accepted response:

```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "queued",
  "createdAt": "2026-06-15T10:00:00Z",
  "statusUrl": "/api/tournaments/550e8400-e29b-41d4-a716-446655440000"
}
```

Validation rejects fewer than two unique bots, duplicate or unknown bot IDs, unavailable bots, unsupported modes, non-positive repetitions, and `maxPly` outside `1..1000`.

### `GET /api/tournaments`

Returns recent in-memory jobs, newest first.

### `GET /api/tournaments/:jobId`

Returns full job status, including selected bots, mode, repetitions, max ply, planned games, completed games, output path, error message, analysis status, analytics run ID/output/error fields, optional analyze URL, optional events URL, and optional result summary.

### `POST /api/tournaments/:jobId/cancel`

Cancels a queued job immediately. Running jobs use cooperative cancellation between games. Terminal jobs return their current state.

### `POST /api/tournaments/:jobId/analyze`

Queues Spark analytics for a succeeded tournament job. The request body is optional:

```json
{"outputPath":"target/spark-analytics/tournament-jobs/<jobId>"}
```

If omitted, the output path defaults to `target/spark-analytics/tournament-jobs/<jobId>`. Newly queued requests return `202 Accepted` with `analysisStatus: "queued"` and the job status URL. Already-succeeded analysis is idempotent and returns the current job state.

Validation rejects unknown jobs, non-succeeded tournament jobs, missing tournament JSONL output, disabled analytics, and jobs whose analysis is already queued or running. Failed analysis can be retried.

### `GET /api/tournaments/:jobId/analysis`

Returns only the analysis fields for a job.

## Job Lifecycle

Jobs move through:

```text
queued -> running -> succeeded
queued -> cancelled
running -> cancelled
running -> failed
```

Phase 10A used an in-memory `Ref[IO, Map[String, TournamentJob]]`. Phase 11A extracted this into
`TournamentJobRepository` with two implementations: `InMemoryTournamentJobRepository` (default)
and `SlickTournamentJobRepository` (when `TOURNAMENT_JOB_STORE=postgres`). The in-memory queue
workers remain unchanged.

Analysis has its own lifecycle:

```text
not_requested -> queued -> running -> succeeded
not_requested -> queued -> running -> failed
failed -> queued -> running -> succeeded
```

Analytics uses a separate background queue and defaults to one worker. Spark is not run on the HTTP request thread.

## Output

Each job writes:

```text
target/arena/tournament-jobs/<jobId>/game-events.jsonl
```

The file contains the same arena JSONL event stream used by existing Spark analytics demos. The analyze endpoint reads that file and writes CSV, optional Parquet, and optional PostgreSQL through the existing Spark analytics configuration.

## Boundaries

`tournament-service` depends on arena core/events, JSONL writer, and bot modules. Routes and job state depend only on the `TournamentAnalyticsRunner` interface. The default runner invokes the Spark analytics job through a background process boundary, which avoids mixing the Scala 3 service classpath with Spark's Scala 2.13 runtime and keeps the execution model replaceable by an external process, Kubernetes job, or analytics-executor service.

`analytics-service` and `/analytics` continue to read PostgreSQL analytics tables. To make a tournament analysis appear there, start tournament-service/Spark with `POSTGRES_WRITE_ENABLED=true` and the `POSTGRES_*` values configured.

## Future Extensions

- Phase 11B: Kafka outbox events on job state transitions.
- Optional automatic analytics trigger after job completion.
- More scheduling modes and larger-scale worker orchestration.

## Manual Run

PowerShell:

```powershell
$env:TOURNAMENT_HTTP_PORT="8085"
sbt tournamentService/run
```

Check service:

```powershell
curl http://localhost:8085/health
curl http://localhost:8085/api/tournaments/bots
```

Create a small job:

```powershell
curl -X POST http://localhost:8085/api/tournaments `
  -H "Content-Type: application/json" `
  -d '{"name":"Heuristic smoke","botIds":["random-bot","capture-first"],"mode":"double-round-robin","repetitions":1,"maxPly":80}'
```
