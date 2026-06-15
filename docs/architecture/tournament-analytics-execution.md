# Tournament Analytics Execution

Phase 10C connects completed tournament jobs to Spark analytics execution without making analysis automatic.

## Flow

1. `tournament-service` starts and monitors tournament jobs.
2. A succeeded job writes JSONL to `target/arena/tournament-jobs/<jobId>/game-events.jsonl`.
3. A client calls `POST /api/tournaments/:jobId/analyze`.
4. `tournament-service` validates the job and queues an analysis request.
5. A background analytics worker invokes Spark analytics through `TournamentAnalyticsRunner`.
6. Spark reads JSONL and writes CSV, optional Parquet, and optional PostgreSQL using the existing Spark configuration.
7. `GET /api/tournaments/:jobId` exposes `analysisStatus`, `analyticsRunId`, `analyticsOutputPath`, and `analyticsErrorMessage`.

The HTTP request only queues work. Spark never runs on the request thread.

## Runner Boundary

Routes and job state depend on the `TournamentAnalyticsRunner` interface. The default implementation is process-backed and runs the existing `sparkAnalytics` job, capturing the run ID emitted by `GameAnalytics.runWithResult`.

This keeps the Spark dependency isolated from tournament job logic and leaves room to replace the runner with an external process, Kubernetes job, or dedicated analytics-executor service.

## PostgreSQL And /analytics

The `/analytics` page reads PostgreSQL through `analytics-service`. For a tournament analysis to appear there, run Spark with:

```powershell
$env:POSTGRES_WRITE_ENABLED="true"
$env:POSTGRES_URL="jdbc:postgresql://localhost:5432/searchess"
$env:POSTGRES_USER="..."
$env:POSTGRES_PASSWORD="..."
```

Other Spark settings remain the existing ones: `POSTGRES_SCHEMA`, `POSTGRES_WRITE_MODE`, `POSTGRES_STRICT_WRITE`, `SPARK_LAKE_WRITE_ENABLED`, `SPARK_LAKE_BASE_PATH`, and `SPARK_LAKE_WRITE_MODE`.

## Non-Goals

- No Kafka.
- No automatic analytics after completion.
- No changes to analytics calculations.
- No changes to analytics-service endpoints.
- No changes to `/analytics` dashboard sections.
- No persistence of tournament jobs beyond in-memory service state.
