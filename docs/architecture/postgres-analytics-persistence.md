# PostgreSQL Analytics Persistence

## Why JSONL is not the long-term persistence layer

JSONL is an append-only flat file format optimised for sequential writes and local portability.
It is excellent for:

- Arena demo input files (committed to the repo as fixtures)
- Local reproducible demos (run once, inspect results)
- Debugging and tracing (human-readable per-event log)
- Test fixtures (GameAnalyticsSpec reads from in-memory JSONL)
- Offline replay

It is not suitable for:

- Shared analytics queries (no indexing, no predicate pushdown)
- Web UI read models (no SQL query interface)
- Long-lived storage (files are transient in CI/CD, containers, and cloud runs)
- Multi-reader access (file locking on writes)

JSONL remains the event transport format. PostgreSQL stores the derived analytics results.

---

## Why PostgreSQL stores analytics results

The Spark batch analytics pipeline computes aggregated read models from raw JSONL events.
These aggregated tables (leaderboard, family comparison, etc.) are the natural fit for a
relational read store because:

- They are small (one row per bot or pairing, not one row per event)
- They are queried by the Web UI with SQL GROUP BY / ORDER BY
- They need to survive container restarts and CI runs
- They can be indexed on `botId`, `run_id`, `created_at` for time-series comparison

PostgreSQL is the analytics output layer. The event contract remains JSON over JSONL/Kafka.

---

## Tables Written

| PostgreSQL table | Source DataFrame | Key columns |
|---|---|---|
| `analytics_leaderboard` | `computeLeaderboard` | `botId`, `totalScore`, `wins`, `draws`, `losses`, `gamesPlayed`, `avgPly`, `winRate` |
| `analytics_head_to_head` | `computeHeadToHead` | `whiteBotId`, `blackBotId`, `gamesPlayed`, `whiteWins`, `blackWins`, `draws`, `whiteScore`, `blackScore` |
| `analytics_avg_game_length` | `computeAvgGameLength` | `whiteBotId`, `blackBotId`, `gamesPlayed`, `avgTotalPly`, `avgDurationMs` |
| `analytics_terminations` | `computeTerminations` | `terminationReason`, `count` |
| `analytics_bot_family_comparison` | `computeBotFamilyComparison` | `family`, `games`, `wins`, `losses`, `draws`, `totalScore`, `winRate` |
| `analytics_strategy_comparison` | `computeStrategyComparison` | `strategyType`, `games`, `wins`, `losses`, `draws`, `totalScore`, `winRate` |
| `analytics_color_performance` | `computeColorPerformance` | `botId`, `gamesAsWhite`, `whiteWins`, `whiteScore`, `gamesAsBlack`, `blackWins`, `blackScore` |
| `analytics_fastest_wins` | `computeFastestWins` | `winnerBotId`, `decisiveGames`, `avgWinPly`, `minWinPly`, `avgWinDurationMs` |
| `analytics_stockfish_comparison` | `computeStockfishComparison` | `botId`, `strategyType`, `games`, `wins`, `draws`, `totalScore`, `avgGameLength`, `winRate` |
| `analytics_family_matchups` | `computeFamilyMatchups` | `whiteBotFamily`, `blackBotFamily`, `games`, `whiteScore`, `blackScore`, `draws` |
| `analytics_searchess_ai_comparison` | `computeSearchessAiComparison` | `opponentBotId`, `opponentFamily`, `games`, `searchessAiWins`, `draws`, `losses`, `score`, `avgGameLength`, `winRate` |

Each table also includes three metadata columns added by `PostgresWriter`:

| Column | Type | Description |
|---|---|---|
| `run_id` | `text` | UUID shared by all tables in one Spark job run |
| `source_path` | `text` | Path to the JSONL input file |
| `created_at` | `text` | ISO-8601 timestamp when the tables were written |

**Column name casing:** Spark JDBC quotes all identifiers, so DataFrame column names are
preserved exactly as written (camelCase). In SQL queries, use double quotes around camelCase
names: `"botId"`, `"totalScore"`, etc.

Write mode is controlled by `POSTGRES_WRITE_MODE` (default: `overwrite`).

---

## Environment Variables

| Variable | Required | Default | Example |
|---|---|---|---|
| `POSTGRES_WRITE_ENABLED` | No | `false` | `true` |
| `POSTGRES_URL` | Yes (if enabled) | — | `jdbc:postgresql://localhost:5432/searchess` |
| `POSTGRES_USER` | Yes (if enabled) | — | `searchess` |
| `POSTGRES_PASSWORD` | Yes (if enabled) | — | `searchess` |
| `POSTGRES_SCHEMA` | No | `public` | `analytics` |
| `POSTGRES_WRITE_MODE` | No | `overwrite` | `append` |
| `POSTGRES_STRICT_WRITE` | No | `false` | `true` |

**`POSTGRES_WRITE_ENABLED`** — Gate. Must be `true` (case-insensitive) to write anything.
If absent or `false`, the job prints an info message and continues without touching PostgreSQL.

**`POSTGRES_WRITE_MODE`**:
- `overwrite` (default) — drops and recreates each table on every run. Suitable for snapshot analytics.
- `append` — adds rows to existing tables, preserving historical runs. Use `run_id` to partition.

**`POSTGRES_STRICT_WRITE`** — If `true`, the Spark job throws an exception and exits with a
non-zero code if any table write fails. Useful in CI pipelines where a broken analytics write
should be treated as a job failure. Default `false` prints a warning and continues.

If `POSTGRES_WRITE_ENABLED=true` but any required variable is missing, a warning is printed,
writes are skipped, and the job still succeeds.

---

## Local PostgreSQL Setup (Docker)

```bash
docker run \
  --name searchess-postgres \
  -e POSTGRES_DB=searchess \
  -e POSTGRES_USER=searchess \
  -e POSTGRES_PASSWORD=searchess \
  -p 5432:5432 \
  -d postgres:16
```

Verify the container is healthy:
```bash
docker exec -it searchess-postgres psql -U searchess -d searchess -c "\dt"
```

The Spark JDBC writer creates tables automatically on the first `overwrite` run.
No manual DDL is needed for the initial setup against the `public` schema.

### Non-public schema

If `POSTGRES_SCHEMA` is set to anything other than `public`, the schema is created
automatically by a JDBC preflight call before the first table write:

```sql
CREATE SCHEMA IF NOT EXISTS "analytics";
```

If the preflight fails (e.g., insufficient privileges), a warning is printed and table writes
are still attempted — they will fail if the schema doesn't exist.

To create the schema manually:
```sql
CREATE SCHEMA IF NOT EXISTS analytics;
GRANT ALL ON SCHEMA analytics TO searchess;
```

---

## Example Run Commands

### With the evaluation tournament JSONL (overwrite mode):

```bash
# Linux/macOS
export POSTGRES_WRITE_ENABLED=true
export POSTGRES_URL=jdbc:postgresql://localhost:5432/searchess
export POSTGRES_USER=searchess
export POSTGRES_PASSWORD=searchess
sbt demoEvaluationSparkAnalytics

# Windows (PowerShell)
$env:POSTGRES_WRITE_ENABLED="true"
$env:POSTGRES_URL="jdbc:postgresql://localhost:5432/searchess"
$env:POSTGRES_USER="searchess"
$env:POSTGRES_PASSWORD="searchess"
sbt demoEvaluationSparkAnalytics
```

### Append mode (preserve history across runs):

```bash
export POSTGRES_WRITE_ENABLED=true
export POSTGRES_WRITE_MODE=append
export POSTGRES_URL=jdbc:postgresql://localhost:5432/searchess
export POSTGRES_USER=searchess
export POSTGRES_PASSWORD=searchess
sbt demoEvaluationSparkAnalytics
```

### Strict mode (fail the job if any write fails):

```bash
export POSTGRES_WRITE_ENABLED=true
export POSTGRES_STRICT_WRITE=true
export POSTGRES_URL=jdbc:postgresql://localhost:5432/searchess
export POSTGRES_USER=searchess
export POSTGRES_PASSWORD=searchess
sbt demoEvaluationSparkAnalytics
```

### Using a custom JSONL file and non-public schema:

```bash
export POSTGRES_WRITE_ENABLED=true
export POSTGRES_SCHEMA=analytics
export POSTGRES_URL=jdbc:postgresql://localhost:5432/searchess
export POSTGRES_USER=searchess
export POSTGRES_PASSWORD=searchess
sbt "sparkAnalytics/run target/arena/evaluation-tournament/game-events.jsonl target/spark-analytics-evaluation"
```

---

## How the Web UI Will Read These Tables

The Web UI (not yet implemented) will query PostgreSQL analytics tables via SQL.
Column names are camelCase (Spark preserves them with quoted identifiers).

Typical access patterns:

```sql
-- Leaderboard page
SELECT "botId", "totalScore", "wins", "draws", "losses", "winRate"
FROM public.analytics_leaderboard
ORDER BY "totalScore" DESC;

-- Bot family comparison chart
SELECT "family", "games", "wins", "winRate"
FROM public.analytics_bot_family_comparison
ORDER BY "totalScore" DESC;

-- SearchessAI head-to-head results
SELECT "opponentBotId", "opponentFamily", "games", "searchessAiWins", "draws", "losses", "winRate"
FROM public.analytics_searchess_ai_comparison
ORDER BY "score" DESC;

-- Latest run only (when using append mode)
SELECT *
FROM public.analytics_leaderboard
WHERE run_id = (
  SELECT run_id FROM public.analytics_leaderboard
  ORDER BY created_at DESC LIMIT 1
);
```

The Web UI has no dependency on Spark code, JSONL files, or arena bots.
It reads only the finished analytics tables from PostgreSQL.

---

## Architecture Boundaries

- `arena-core`, `GameRunner`, `TournamentRunner` have **no** PostgreSQL dependency.
- Bot modules (`arenaBotsHeuristic`, `arenaBotsUci`, `arenaBotsAi`) have **no** PostgreSQL dependency.
- PostgreSQL JDBC driver (`org.postgresql:postgresql:42.7.11`) is added only to `sparkAnalytics`.
- The Spark analytics module reads raw JSONL and writes derived analytics results.

---

## Current Limitations

- **Raw events/moves are not stored in PostgreSQL.** Only computed analytics tables are
  persisted. Raw `GameStarted`, `MovePlayed`, `GameFinished` events remain in JSONL/Kafka.
- **No migrations framework.** `overwrite` mode drops and recreates tables; `append` mode
  requires a stable schema across runs. Column additions will break existing `append` tables.
- **No Kafka-to-PostgreSQL consumer.** Streaming raw events to a PostgreSQL event store
  is Phase 9+.
