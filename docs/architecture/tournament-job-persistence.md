# Tournament Job Persistence (Phase 11A)

## Overview

The `tournament-service` manages the lifecycle of Bot Evaluation Arena tournament runs and their
downstream Spark analytics jobs. Phase 11A adds durable PostgreSQL storage so that job state
survives service restarts and is accessible to future Kafka outbox publishing (Phase 11B).

---

## Why memory mode exists

Memory mode (`TOURNAMENT_JOB_STORE=memory`, the default) is the original implementation and is
retained for:

- Local development with no infrastructure.
- Unit / integration tests that don't require Docker or a live database.
- Environments where restart survivability is not needed (e.g. single-run CI pipelines).

In memory mode all job state lives in a `cats.effect.Ref[IO, Map[String, TournamentJob]]` and
is lost when the process exits.

---

## Why PostgreSQL mode is needed

For a deployed, production-grade service:

1. **Restart survivability** — job list persists across service restarts.
2. **Kafka readiness (Phase 11B)** — each named state-transition method (`markRunning`,
   `markSucceeded`, etc.) is a single code boundary. An outbox event can be inserted in the same
   database transaction immediately after the state-change SQL.
3. **Multiple replicas / blue-green deploys** — a shared database allows multiple instances to
   read consistent job state (write contention is acceptable given single-writer semantics in
   Phase 11A).

---

## Table schema

### `tournament_jobs`

One row per tournament job. Created by `POST /api/tournaments`, updated throughout execution.

| Column           | Type    | Notes                                          |
|------------------|---------|------------------------------------------------|
| `job_id`         | TEXT PK | UUID assigned at creation                      |
| `name`           | TEXT    | Optional user-supplied name                    |
| `status`         | TEXT    | `queued` `running` `succeeded` `failed` `cancelled` |
| `selected_bot_ids` | TEXT  | JSON array of bot ID strings                   |
| `mode`           | TEXT    | Always `double-round-robin`                    |
| `repetitions`    | INT     |                                                |
| `max_ply`        | INT     |                                                |
| `seed`           | BIGINT  | Optional RNG seed                              |
| `planned_games`  | INT     | `n*(n-1)*repetitions`                          |
| `completed_games`| INT     | Incremented atomically after each game         |
| `output_path`    | TEXT    | Path to `game-events.jsonl`                    |
| `error_message`  | TEXT    | Set on failure                                 |
| `result_summary` | TEXT    | Human-readable completion / cancel message     |
| `events_url`     | TEXT    | Relative URL to events endpoint                |
| `analyze_url`    | TEXT    | Relative URL to trigger analytics              |
| `created_at`     | TEXT    | ISO-8601 instant                               |
| `started_at`     | TEXT    | ISO-8601 instant                               |
| `finished_at`    | TEXT    | ISO-8601 instant                               |

### `analysis_jobs`

At most **one row per tournament job** (enforced via `UNIQUE (tournament_job_id)`). Upserted on
`POST /api/tournaments/:jobId/analyze`; the same row is updated on retry (mutable).

| Column             | Type    | Notes                                        |
|--------------------|---------|----------------------------------------------|
| `analysis_job_id`  | TEXT PK | UUID; regenerated on upsert insert path      |
| `tournament_job_id`| TEXT    | Logical FK to `tournament_jobs.job_id`       |
| `status`           | TEXT    | `queued` `running` `succeeded` `failed`      |
| `input_path`       | TEXT    | Tournament `game-events.jsonl` path          |
| `output_path`      | TEXT    | Spark analytics output directory             |
| `analytics_run_id` | TEXT    | Populated by Spark on success                |
| `error_message`    | TEXT    | Set on failure                               |
| `created_at`       | TEXT    | ISO-8601 instant (first queue time)          |
| `started_at`       | TEXT    | ISO-8601 instant                             |
| `finished_at`      | TEXT    | ISO-8601 instant                             |

**Relationship:** `analysis_jobs.tournament_job_id` logically references
`tournament_jobs.job_id`. No database-level FK constraint is enforced (simplified schema);
orphan rows cannot occur through normal service operation.

---

## Status lifecycle

### Tournament job statuses

```
queued → running → succeeded
                 → failed
       → cancelled         (HTTP cancel while queued)
running → cancelled        (HTTP cancel while running; worker finalizes finishedAt)
```

`queued`, `running`, and `cancelled` (of a running job) are the only non-terminal states.
Once terminal (`succeeded`, `failed`, `cancelled` with `finishedAt` set), a job never changes
status again.

### Analysis job statuses

`not_requested` is **not** a persisted row. The derived field means no row exists for the
tournament in `analysis_jobs`.

```
[no row] → queued → running → succeeded
                             → failed   (retry: queued again via upsert)
```

---

## `analysisStatus` derivation

The HTTP response field `analysisStatus` is derived as:

- No `analysis_jobs` row for this tournament → `not_requested`
- Row with `status = 'queued'` → `queued`
- Row with `status = 'running'` → `running`
- Row with `status = 'succeeded'` → `succeeded`
- Row with `status = 'failed'` → `failed`

---

## Retry semantics

A failed analysis can be retried via `POST /api/tournaments/:jobId/analyze`. The existing
`analysis_jobs` row is reset to `queued` via `ON CONFLICT (tournament_job_id) DO UPDATE`.
This keeps the schema simple (one mutable row) and avoids accumulating historical analysis
rows.

---

## Relationship to analytics-service PostgreSQL tables

`analytics-service` reads from **separate** tables written by the Spark `GameAnalyticsJob`:
`analytics_leaderboard`, `analytics_bot_family`, etc. These are analytics *read-model* tables.

`tournament_jobs` and `analysis_jobs` are **operational state** tables. They must not share
the same env var prefix (`ANALYTICS_POSTGRES_*`). Tournament service uses its own
`TOURNAMENT_POSTGRES_*` variables.

---

## Relationship to Kafka outbox (Phase 11B — implemented)

Phase 11B added the `tournament_outbox_events` table and atomic outbox inserts.
See [tournament-outbox-events.md](tournament-outbox-events.md) for the full schema,
event type list, payload contracts, and publisher design.

Each named transition in `SlickTournamentJobRepository` wraps its state-change SQL and
an `INSERT INTO tournament_outbox_events` in a single Slick `.transactionally` DBIO.
Both commit or neither commits.

| Method                  | Outbox event emitted              |
|-------------------------|-----------------------------------|
| `createJob`             | `TournamentJobCreated`            |
| `markRunning`           | `TournamentJobStarted`            |
| `incrementCompletedGames` | `TournamentGameCompleted`       |
| `markSucceeded`         | `TournamentJobSucceeded`          |
| `markFailed`            | `TournamentJobFailed`             |
| `cancelJob` (Queued)    | `TournamentJobCancelled`          |
| `finalizeCancelled`     | `TournamentJobCancelled`          |
| `queueAnalysis`         | `AnalysisJobQueued`               |
| `markAnalysisRunning`   | `AnalysisJobStarted`              |
| `markAnalysisSucceeded` | `AnalysisJobSucceeded`            |
| `markAnalysisFailed`    | `AnalysisJobFailed`               |

Phase 11C/11D will add the Kafka publisher that reads `pending` rows and publishes them.

---

## Configuration

| Env var                          | Default   | Required when         |
|----------------------------------|-----------|-----------------------|
| `TOURNAMENT_JOB_STORE`           | `memory`  | —                     |
| `TOURNAMENT_POSTGRES_URL`        | —         | `jobStore=postgres`   |
| `TOURNAMENT_POSTGRES_USER`       | —         | `jobStore=postgres`   |
| `TOURNAMENT_POSTGRES_PASSWORD`   | —         | `jobStore=postgres`   |
| `TOURNAMENT_POSTGRES_SCHEMA`     | `public`  | —                     |

Schema name is validated as `[a-zA-Z_][a-zA-Z0-9_]*`.

Tables are created with `CREATE TABLE IF NOT EXISTS` on service startup. No Flyway/Liquibase.

---

## Manual verification checklist

```
TOURNAMENT_JOB_STORE=postgres
TOURNAMENT_POSTGRES_URL=jdbc:postgresql://localhost:5432/searchess
TOURNAMENT_POSTGRES_USER=searchess
TOURNAMENT_POSTGRES_PASSWORD=searchess
TOURNAMENT_POSTGRES_SCHEMA=public
```

1. Start PostgreSQL and tournament-service with the vars above.
2. Create a small tournament (`POST /api/tournaments` with random-bot + capture-first).
3. Wait for job to succeed (`GET /api/tournaments/:jobId`).
4. Restart tournament-service.
5. Verify job still appears in `GET /api/tournaments` (Recent Jobs).
6. Run analytics (`POST /api/tournaments/:jobId/analyze`).
7. Restart tournament-service again.
8. Verify `analysisStatus` persisted correctly in `GET /api/tournaments/:jobId`.
