# Spark Batch/Streaming Shared Pipeline

Phase 9C separates source acquisition from Spark analytics transformations.

## Separation Of Concerns

Source readers are responsible for acquiring bytes or rows:

- JSONL batch source reads files through `BronzeEvents.loadEvents`.
- Kafka streaming source reads Kafka records and passes the `value` column to `KafkaEventParser`.

Transformation code is responsible for turning Bronze events into analytics:

- `AnalyticsPipeline.buildSilver` creates event-specific Silver views.
- `AnalyticsPipeline.buildGold` creates the existing batch Gold analytics tables.
- `AnalyticsPipeline.buildDataQuality` creates the GameFinished data quality table.
- `AnalyticsPipeline.buildBatchResult` packages the Silver, Gold, and data quality outputs for batch-style workflows.

The pipeline does not read files, connect to Kafka, write CSV, write Parquet, write PostgreSQL, or know about analytics-service or the Web UI.

## JSONL Batch Path

```text
JSONL file
  -> BronzeEvents.loadEvents with GameEventSchemas.eventSchema
  -> AnalyticsPipeline.buildSilver
  -> AnalyticsPipeline.buildGold
  -> GameAnalytics.run prints tables and writes CSV, optional Parquet lake, optional PostgreSQL
```

Existing CSV folder names, PostgreSQL table names, and optional Parquet lake paths remain unchanged.

## Kafka Streaming Path

```text
Kafka value column
  -> KafkaEventParser.parseEvents with GameEventSchemas.eventSchema
  -> SilverEvents.gameFinished
  -> StreamingGoldAnalytics subset
  -> streaming sink such as console
```

`KafkaEventParser` is testable without a broker by creating a static DataFrame with a JSON string `value` column.

## Shared Schema

Both paths reuse `GameEventSchemas.eventSchema`. This keeps the flat event contract consistent across file and Kafka sources and avoids separate schema inference behavior for each runtime.

## Batch-Only Analytics For Now

The existing Gold analytics in `GameAnalytics.compute*` remain the full batch surface:

- leaderboard
- head-to-head
- terminations
- average game length
- bot family comparison
- strategy comparison
- color performance
- fastest wins
- Stockfish comparison
- family matchups
- SearchessAI comparison

Some of these use ordering, joins, or reporting shapes that are best left as batch outputs until a streaming serving design is chosen.

## Streaming-Compatible Subset

`StreamingGoldAnalytics` currently includes:

- live leaderboard
- terminations count
- bot family comparison

These avoid global `orderBy` so they can be used with streaming aggregation output modes.

## Current Limitations

- Phase 9C does not add a real Kafka broker smoke test.
- Streaming output is still a simple skeleton, not a serving API.
- PostgreSQL remains the serving/read model for analytics-service and Web UI.
- No Elo, new bots, new endpoints, new charts, or PostgreSQL schema changes are included.
