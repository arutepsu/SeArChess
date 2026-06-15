# Tournament Builder Web UI

Phase 10B adds a Web UI page at `/tournaments` for creating and monitoring tournament-service jobs.

## Purpose

The page lets a user select available bots, configure a double round-robin tournament, submit the job to tournament-service, monitor progress until the job succeeds, fails, or is cancelled, and explicitly run Spark analytics for succeeded jobs.

It does not read JSONL in the browser, call analytics-service directly, or update the analytics dashboard sections. Spark execution is requested through tournament-service and runs in that service's background analytics worker.

## Required Backend

| Service | Default port | Purpose |
|---------|--------------|---------|
| tournament-service | 8085 | Bot registry and tournament job lifecycle |

Start tournament-service first:

```powershell
$env:TOURNAMENT_HTTP_PORT="8085"
sbt tournamentService/run
```

## Route

```text
/tournaments
```

The top auth/nav bar includes a **Tournaments** link near **Analytics**.

## Environment

| Var | Default | Description |
|-----|---------|-------------|
| `VITE_TOURNAMENT_API_BASE_URL` | empty | Full tournament API base URL; empty means same origin |
| `VITE_TOURNAMENT_PROXY_TARGET` | `http://localhost:8085` in `.env.tournaments` | Vite dev proxy target |

Dev mode:

```powershell
cd apps/web-ui
npm run dev:tournaments
```

The Vite proxy registers `/api/tournaments` before the generic `/api` proxy, matching the analytics proxy pattern.

## User Workflow

1. Open `/tournaments`.
2. The page loads `GET /api/tournaments/bots` and `GET /api/tournaments`.
3. Select at least two available bots.
4. Configure name, mode, repetitions, max ply, and optional seed.
5. Start the job with `POST /api/tournaments`.
6. Inspect recent jobs and selected job details.
7. Queued/running jobs poll `GET /api/tournaments/:jobId` every 2 seconds.
8. Cancel queued/running jobs with `POST /api/tournaments/:jobId/cancel`.
9. When a job succeeds, click **Run analytics** to call `POST /api/tournaments/:jobId/analyze`.
10. While analysis is queued/running, job details poll every 2 seconds.
11. When analysis succeeds, the page shows the analytics run ID, analytics output path, and a link to `/analytics`.

## UI Sections

- Create Tournament: grouped bot cards and config form.
- Recent Jobs: status, selected bots, created/finished times, progress.
- Job Details: status, progress, output path, error message, analysis status badge, analytics run ID, analytics output path, analytics error message, run analytics action, and cancel action.

For succeeded jobs, the page shows the JSONL output path returned by tournament-service. For succeeded analysis, it tells the user: "Analytics written. Open /analytics and select the new run."

## Limitations

- Spark analytics are not triggered automatically yet.
- `/analytics` only shows the new run when Spark was configured to write PostgreSQL tables with `POSTGRES_WRITE_ENABLED=true` and the required `POSTGRES_*` values.
- Jobs remain in tournament-service memory.
- The browser does not read JSONL or access the filesystem directly.
- Kafka is not required.
- Stockfish and SearchessAI are optional and appear disabled unless their service-side environment variables are configured.
