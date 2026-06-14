# Analytics Read API — Phase 8E

## Purpose

`analytics-service` is a thin HTTP read API that exposes the PostgreSQL analytics tables
written by the Spark batch analytics job (`spark-analytics`). It is consumed by the Web UI
to display tournament results, bot rankings, and performance breakdowns — with a run selector
so any historical Spark run can be inspected, not just the latest.

It does **not** run Spark, read JSONL, write analytics tables, or depend on any arena or
game engine modules.

---

## Port

| Service            | Default port |
|--------------------|-------------|
| analytics-service  | **8084**     |

---

## Configuration

| Env var                    | Default       | Required | Description                         |
|----------------------------|---------------|----------|-------------------------------------|
| `ANALYTICS_HTTP_HOST`      | `0.0.0.0`     | no       | HTTP bind address                   |
| `ANALYTICS_HTTP_PORT`      | `8084`        | no       | HTTP bind port (1–65535)            |
| `ANALYTICS_POSTGRES_URL`   | —             | **yes**  | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/searchess` |
| `ANALYTICS_POSTGRES_USER`  | `searchess`   | no       | PostgreSQL username                 |
| `ANALYTICS_POSTGRES_PASSWORD` | `""`       | no       | PostgreSQL password                 |
| `ANALYTICS_POSTGRES_SCHEMA` | `public`    | no       | Schema containing `analytics_*` tables. Must match `[A-Za-z_][A-Za-z0-9_]*` |

---

## Endpoints

### `GET /health`

Liveness probe.

```json
{"status": "ok", "service": "searchess-analytics-service"}
```

---

### `GET /api/analytics/runs`

Lists all analytics runs recorded in the database, newest first.

```json
{
  "runs": [
    {"runId": "550e8400-e29b-41d4-a716-446655440001", "sourcePath": "/data/games-v2.jsonl", "createdAt": "2026-06-14T10:00:00Z"},
    {"runId": "550e8400-e29b-41d4-a716-446655440000", "sourcePath": "/data/games.jsonl",    "createdAt": "2026-06-13T10:00:00Z"}
  ]
}
```

```bash
curl http://localhost:8084/api/analytics/runs
```

---

### Latest-run endpoints

Use these when you want data from the most recent Spark run without knowing its `runId`.

| Endpoint | Source table |
|----------|-------------|
| `GET /api/analytics/latest/leaderboard`    | `analytics_leaderboard` |
| `GET /api/analytics/latest/bot-families`   | `analytics_bot_family_comparison` |
| `GET /api/analytics/latest/strategies`     | `analytics_strategy_comparison` |
| `GET /api/analytics/latest/searchess-ai`   | `analytics_searchess_ai_comparison` |
| `GET /api/analytics/latest/stockfish`      | `analytics_stockfish_comparison` |
| `GET /api/analytics/latest/avg-game-length` | `analytics_avg_game_length` |

All return `{ "rows": [...] }`. The latest run is resolved via:

```sql
WHERE run_id = (SELECT run_id FROM <schema>.analytics_leaderboard ORDER BY created_at DESC LIMIT 1)
```

```bash
curl http://localhost:8084/api/analytics/latest/leaderboard
curl http://localhost:8084/api/analytics/latest/bot-families
curl http://localhost:8084/api/analytics/latest/strategies
curl http://localhost:8084/api/analytics/latest/searchess-ai
curl http://localhost:8084/api/analytics/latest/stockfish
curl http://localhost:8084/api/analytics/latest/avg-game-length
```

---

### Run-specific endpoints

Use these to fetch data for a specific `runId`. The `runId` must be a valid UUID
(`xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`). Run IDs are assigned by the Spark analytics
writer and are available from `GET /api/analytics/runs`.

| Endpoint | Source table |
|----------|-------------|
| `GET /api/analytics/runs/:runId/leaderboard`    | `analytics_leaderboard` |
| `GET /api/analytics/runs/:runId/bot-families`   | `analytics_bot_family_comparison` |
| `GET /api/analytics/runs/:runId/strategies`     | `analytics_strategy_comparison` |
| `GET /api/analytics/runs/:runId/searchess-ai`   | `analytics_searchess_ai_comparison` |
| `GET /api/analytics/runs/:runId/stockfish`      | `analytics_stockfish_comparison` |
| `GET /api/analytics/runs/:runId/avg-game-length` | `analytics_avg_game_length` |

All return `{ "rows": [...] }`.

```bash
RUN_ID="550e8400-e29b-41d4-a716-446655440000"
curl "http://localhost:8084/api/analytics/runs/$RUN_ID/leaderboard"
curl "http://localhost:8084/api/analytics/runs/$RUN_ID/bot-families"
curl "http://localhost:8084/api/analytics/runs/$RUN_ID/strategies"
curl "http://localhost:8084/api/analytics/runs/$RUN_ID/searchess-ai"
curl "http://localhost:8084/api/analytics/runs/$RUN_ID/stockfish"
curl "http://localhost:8084/api/analytics/runs/$RUN_ID/avg-game-length"
```

#### runId validation

`runId` is validated against the UUID pattern before any database call:

```
[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}
```

An invalid format returns **400 Bad Request** before touching the database:

```json
{"code": "INVALID_RUN_ID", "message": "Invalid run ID: expected UUID format, got 'not-a-uuid'"}
```

The `runId` is passed as a PreparedStatement bind parameter (`$runId` in Slick SQL) — it is
never interpolated as raw SQL string. Only the schema name uses literal interpolation (`#$schema`),
which is validated by the `[A-Za-z_][A-Za-z0-9_]*` regex in `AnalyticsServiceConfig` before
the repository is constructed.

---

## Response format

### Success

```json
{ "rows": [ { ...row fields... } ] }
```

Empty result (run exists but has no data for that section):

```json
{ "rows": [] }
```

### Error: invalid runId (400)

```json
{"code": "INVALID_RUN_ID", "message": "Invalid run ID: expected UUID format, got 'not-a-uuid'"}
```

### Error: database failure (503)

```json
{"code": "ANALYTICS_UNAVAILABLE", "message": "Analytics query failed: relation \"analytics.analytics_leaderboard\" does not exist"}
```

---

## Latest vs selected run — behavior comparison

| Scenario | Latest endpoint | Run-specific endpoint |
|----------|-----------------|-----------------------|
| Spark not run yet | 503 | 503 |
| Invalid UUID format | N/A | 400 |
| Run exists, has data | 200 with rows | 200 with rows |
| Run exists, no data for section | 200 with empty rows | 200 with empty rows |
| runId not in database | N/A | 200 with empty rows |

---

## Table mapping

| Endpoint suffix | PostgreSQL table |
|-----------------|-----------------|
| `/runs` (listing) | `analytics_leaderboard` (DISTINCT run_id) |
| `/leaderboard` | `analytics_leaderboard` |
| `/bot-families` | `analytics_bot_family_comparison` |
| `/strategies` | `analytics_strategy_comparison` |
| `/searchess-ai` | `analytics_searchess_ai_comparison` |
| `/stockfish` | `analytics_stockfish_comparison` |
| `/avg-game-length` | `analytics_avg_game_length` |

---

## Prerequisites

The analytics tables must be created by running `spark-analytics` with `POSTGRES_WRITE_ENABLED=true`.
To accumulate multiple runs (for the run selector), use `POSTGRES_WRITE_MODE=append` on subsequent
runs (default mode is `overwrite` which replaces existing data).

Optionally pre-create the schema:

```sql
CREATE SCHEMA IF NOT EXISTS analytics;
```

See [postgres-analytics-persistence.md](postgres-analytics-persistence.md) for the full table
schema and Spark configuration.

---

## Architecture Boundaries

```
spark-analytics  ──writes──►  PostgreSQL analytics.*
                                      │
analytics-service  ──reads──────────►│
                                      │
Web UI (/analytics)  ──HTTP──────────►  analytics-service :8084
  run selector → GET /api/analytics/runs
  section data  → GET /api/analytics/runs/:runId/<section>
                  GET /api/analytics/latest/<section>
```

- `analytics-service` depends only on: `observability`, http4s, Slick, PostgreSQL JDBC driver, ujson.
- It does **not** depend on: `arena-core`, `game-service`, `history-service`, any bot module, or `spark-analytics`.
- Write path (Spark) and read path (analytics-service) are completely decoupled — they share only the PostgreSQL schema.

---

## Running

```bash
export ANALYTICS_POSTGRES_URL=jdbc:postgresql://localhost:5432/searchess
export ANALYTICS_POSTGRES_USER=searchess
export ANALYTICS_POSTGRES_PASSWORD=secret
export ANALYTICS_POSTGRES_SCHEMA=analytics
sbt analyticsService/run

# Verify
curl http://localhost:8084/health
curl http://localhost:8084/api/analytics/runs
curl "http://localhost:8084/api/analytics/runs/550e8400-e29b-41d4-a716-446655440000/leaderboard"
curl http://localhost:8084/api/analytics/latest/leaderboard
```

---

## Tests

```bash
sbt testAnalyticsService
# or
sbt analyticsService/test
```

Tests use `InMemoryAnalyticsRepository` — no PostgreSQL required. Coverage:

| Route group | Cases covered |
|-------------|---------------|
| `GET /health` | 200 ok |
| `GET /api/analytics/runs` | empty list, single run, two runs newest-first, 503 |
| `GET /latest/*` (6 endpoints) | 200 with data, 200 empty, 503 each |
| `GET /runs/:runId/*` (6 endpoints) | 200 with data, 503 each |
| Invalid runId | 400 INVALID_RUN_ID for all 6 run-specific endpoints |
| Unknown route | 404 |
