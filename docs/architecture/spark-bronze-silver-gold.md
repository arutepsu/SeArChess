# Spark Bronze/Silver/Gold Analytics

Phase 9A refactors the batch Spark analytics job into explicit ingestion layers while preserving the existing CSV folders, PostgreSQL table writes, and public analytics methods.

## Why Replace Schema Inference

The previous batch loader used `spark.read.json(path)`, so Spark inferred column names and types from whatever rows were present in a particular JSONL file. That works for small demos, but mixed event streams are sparse by design: `GameStarted`, `MovePlayed`, and `GameFinished` rows share one file and each event type only fills part of the contract.

An explicit schema makes the reader stable when a file contains only some event types, when nullable fields are absent, or when later events add fields. It also gives batch and future streaming readers a shared contract to converge on.

## Bronze

`BronzeEvents.loadEvents` reads JSONL files with `GameEventSchemas.eventSchema`.

Responsibilities:

- Load raw game events from the current file input.
- Use an explicit nullable schema instead of inference.
- Keep all event types in one DataFrame.
- Drop malformed rows when Spark records them through the configured corrupt-record column.
- Avoid analytics logic.

## Silver

`SilverEvents` creates typed event views from Bronze:

- `gameStarted`
- `movePlayed`
- `gameFinished`

The Silver layer filters by `eventType`, selects the event-specific columns, and casts simple numeric fields such as ply counts and durations. It intentionally avoids domain-heavy enrichment so existing analytics behavior stays unchanged.

## Gold

The existing `GameAnalytics.compute*` methods are the Gold layer for Phase 9A. They continue to compute leaderboard, head-to-head, terminations, game length, bot family, strategy, color, fastest wins, Stockfish, family matchup, and SearchessAI comparison outputs from Silver `GameFinished` rows.

`GameAnalytics.run` still prints the same analytics tables, writes the same CSV output folders, and writes the same PostgreSQL analytics tables when configured.

## Data Quality

`DataQualityChecks.gameFinishedChecks` returns a small check table with:

- `checkName`
- `failedRows`
- `severity`

The job prints this table but does not fail the Spark run yet.

## File Input Now, Kafka Later

Phase 9A keeps the batch input as JSONL files. The explicit schema and Silver views are deliberately reusable, so a later Kafka streaming phase can parse Kafka values into the same Bronze shape and apply the same Silver/Gold logic.

## Current Limitations

- Data quality failures are reported only; they do not stop the job.
- Bronze still reads JSONL files, not Parquet or Kafka.
- The Gold layer remains in `GameAnalytics` to avoid unnecessary churn.
- No Elo, confidence intervals, new PostgreSQL schema, Web UI changes, or analytics-service changes are included in this phase.
