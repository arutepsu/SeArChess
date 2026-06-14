# Tournament Builder Backend

Phase 10A adds `tournament-service`, a small HTTP service that owns Bot Evaluation Arena tournament job lifecycle.

## Purpose

The service provides the backend foundation for a future Web UI tournament builder. A client can list tournament-capable bots, create a tournament job, inspect job progress, and cancel queued or running jobs.

It starts arena jobs and writes game events as JSONL. It does not run Spark, write analytics PostgreSQL tables, call analytics-service, or update the Web UI dashboard.

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
| `STOCKFISH_PATH` | unset | Enables Stockfish bot entries when it points to a file |
| `SEARCHESS_AI_BASE_URL` | unset | Enables `searchess-ai-v1` |

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

Returns full job status, including selected bots, mode, repetitions, max ply, planned games, completed games, output path, error message, `analyticsRunId` as `null`, optional events URL, and optional result summary.

### `POST /api/tournaments/:jobId/cancel`

Cancels a queued job immediately. Running jobs use cooperative cancellation between games. Terminal jobs return their current state.

## Job Lifecycle

Jobs move through:

```text
queued -> running -> succeeded
queued -> cancelled
running -> cancelled
running -> failed
```

Phase 10A uses an in-memory `Ref[IO, Map[String, TournamentJob]]` and a single `Queue[IO, String]` worker. This avoids concurrent Stockfish process pressure and is enough for local demo use.

## Output

Each job writes:

```text
target/arena/tournament-jobs/<jobId>/game-events.jsonl
```

The file contains the same arena JSONL event stream used by existing Spark analytics demos.

## Boundaries

`tournament-service` depends on arena core/events, JSONL writer, and bot modules. It does not depend on Spark analytics, analytics-service, PostgreSQL, Kafka, or the Web UI.

Spark is not run directly in this phase because analytics triggering needs a separate lifecycle decision: when to run, where to store results, how to surface run IDs, and how to handle failed or cancelled tournaments.

## Future Extensions

- Web UI tournament builder that calls these endpoints.
- Analytics trigger after job completion.
- Persisted job store instead of in-memory state.
- Kafka event output when the shared Kafka runtime is ready.
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
