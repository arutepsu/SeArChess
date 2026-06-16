# Spark Parquet Lake

Phase 9B adds optional Parquet output for the Spark analytics lake. It does not replace the existing CSV demo output or PostgreSQL serving tables.

## Why Parquet

JSONL remains the simple event interchange format produced by arena runs. It is append-friendly and easy to inspect, but it is not ideal for repeated analytics scans.

Parquet is added as a local data lake format because it stores typed columnar data efficiently and preserves the Bronze/Silver/Gold layer shape for later batch or streaming reuse.

PostgreSQL remains the serving/read model used by analytics-service and the Web UI. No analytics-service API, Web UI behavior, or PostgreSQL schema changes are part of this phase.

## Lake Layout

When `SPARK_LAKE_WRITE_ENABLED=true`, Spark writes:

```text
target/spark-lake/
  bronze/
    events/
  silver/
    game-started/
    move-played/
    game-finished/
  gold/
    leaderboard/
    head-to-head/
    terminations/
    avg-game-length/
    bot-family-comparison/
    strategy-comparison/
    color-performance/
    fastest-wins/
    stockfish-comparison/
    family-matchups/
    searchess-ai-comparison/
    data-quality/
```

Every Parquet table receives metadata columns:

- `run_id`
- `source_path`
- `created_at`
- `layer`

If `SPARK_LAKE_PARTITION_BY_RUN_ID` is enabled, writes use `partitionBy("run_id")`.

## Environment Variables

| Variable | Default | Notes |
| --- | --- | --- |
| `SPARK_LAKE_WRITE_ENABLED` | `false` | Set to `true` to write Parquet lake output. |
| `SPARK_LAKE_BASE_PATH` | `target/spark-lake` | Root folder for Bronze/Silver/Gold Parquet tables. |
| `SPARK_LAKE_WRITE_MODE` | `overwrite` | Allowed values are `overwrite` and `append`. |
| `SPARK_LAKE_PARTITION_BY_RUN_ID` | `true` | Set to `false` to write without run-id partition folders. |

## Local Demo

PowerShell:

```powershell
$env:SPARK_LAKE_WRITE_ENABLED="true"
$env:SPARK_LAKE_BASE_PATH="target/spark-lake"
$env:SPARK_LAKE_WRITE_MODE="overwrite"
sbt demoSearchessAiSparkAnalytics
```

Verify these folders exist:

```text
target/spark-lake/bronze/events
target/spark-lake/silver/game-finished
target/spark-lake/gold/leaderboard
```

## Current Limitations

- Parquet output is optional and local-path oriented.
- Lake writes are not the serving path for analytics-service or Web UI.
- Data quality checks are written as Gold Parquet but still do not fail the Spark job.
- Kafka streaming, Elo, new analytics endpoints, new charts, and PostgreSQL schema changes are out of scope for this phase.
