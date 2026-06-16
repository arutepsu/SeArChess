# Searchess Analytics Platform — Feature Summary

## Purpose

This document summarizes the analytics and bot-evaluation features added to the Searchess project so far.

The goal of this work is to turn Searchess from a chess application with isolated bot/game behavior into a measurable bot evaluation platform. The system can run tournaments, emit structured game events, process them with Apache Spark, persist analytics results, expose them through a read API, and visualize them in the Web UI.

---

## 1. Bot Evaluation Arena

A new bot evaluation flow was introduced to let different chess bots play controlled games against each other.

### Main responsibilities

* Schedule bot-vs-bot games.
* Run games through a central `GameRunner`.
* Validate moves through the domain/game rules.
* Emit normalized game events.
* Support repeatable tournament-style evaluation.

### Bot families

The design supports multiple bot integration families:

* Heuristic bots
* UCI engine bots
* AI service bots
* Imported/external bots for future expansion

### Implemented bot types

Current implemented/evaluated bot types include:

* `RandomBot`
* `CaptureFirstBot`
* `MaterialGreedyBot`
* `StockfishDepth1`
* `StockfishDepth3`
* `StockfishFast`
* `StockfishSlow`
* `SearchessAI-v1`

The arena is designed so Spark does not depend on individual bot implementations. Spark reads only normalized event data.

---

## 2. Event-Based Architecture

Game execution emits structured events instead of coupling analytics directly to game objects.

### Main event types

* `GameStarted`
* `MovePlayed`
* `GameFinished`

### Event output

Events are written as JSON Lines:

```text
game-events.jsonl
```

Each line contains one complete JSON event object. This gives a simple file-first integration path and prepares the same event contract for Kafka later.

### Key design decision

The event schema is the boundary between the game/tournament world and the analytics world.

```text
Arena / GameRunner -> GameEvent JSON -> Spark Analytics
```

Spark does not know about bot internals, UCI processes, HTTP AI calls, or chess engine implementation details.

---

## 3. Spark Batch Analytics

Apache Spark reads the generated event file and computes analytics from `GameFinished` events.

### Existing batch analytics

Spark computes:

* Leaderboard
* Head-to-head results
* Termination reasons
* Average game length
* Bot family comparison
* Strategy comparison
* Color performance
* Fastest winning bots
* Stockfish configuration comparison
* Family matchups
* SearchessAI-v1 comparison
* Elo ratings

### Output formats

Spark writes results to:

* Console tables
* CSV folders
* Optional Parquet lake
* Optional PostgreSQL analytics tables

---

## 4. PostgreSQL Analytics Persistence

Spark can persist analytics tables into PostgreSQL.

### Purpose

PostgreSQL acts as the serving/read model for analytics-service and the Web UI.

### Metadata columns

The shared writer adds:

* `run_id`
* `source_path`
* `created_at`

This allows multiple analytics runs to be stored and selected later.

### Main analytics tables

Examples:

* `analytics_leaderboard`
* `analytics_head_to_head`
* `analytics_terminations`
* `analytics_avg_game_length`
* `analytics_bot_family_comparison`
* `analytics_strategy_comparison`
* `analytics_color_performance`
* `analytics_fastest_wins`
* `analytics_stockfish_comparison`
* `analytics_searchess_ai_comparison`
* `analytics_elo_ratings`

---

## 5. Analytics Read API

A dedicated `analytics-service` was added as a thin HTTP read API over PostgreSQL analytics tables.

### Responsibilities

* Read analytics tables from PostgreSQL.
* Expose JSON endpoints for the Web UI.
* Support latest-run and run-specific queries.
* Keep Spark, game logic, and UI decoupled.

### Endpoint groups

Latest-run endpoints include:

* `GET /api/analytics/latest/leaderboard`
* `GET /api/analytics/latest/bot-families`
* `GET /api/analytics/latest/strategies`
* `GET /api/analytics/latest/searchess-ai`
* `GET /api/analytics/latest/stockfish`
* `GET /api/analytics/latest/avg-game-length`
* `GET /api/analytics/latest/elo-ratings`
* `GET /api/analytics/latest/terminations`
* `GET /api/analytics/latest/color-performance`
* `GET /api/analytics/latest/fastest-wins`

Run-specific endpoints follow this pattern:

```text
GET /api/analytics/runs/:runId/<section>
```

The `runId` is validated as a UUID before database access.

---

## 6. Web UI Analytics Dashboard

The Web UI gained an `/analytics` page that consumes analytics-service.

### UI features

The page displays:

* Run selector
* Leaderboard
* Elo Ratings
* Bot Family Comparison
* Strategy Comparison
* Searchess AI vs Opponents
* Stockfish Variants
* Termination Reasons
* Fastest Winning Bots
* Color Performance
* Average Game Length by Pairing

### Architecture boundary

The Web UI does not:

* read JSONL files
* run Spark
* connect to PostgreSQL directly
* know about arena/game internals

It only calls analytics-service.

```text
Web UI -> analytics-service -> PostgreSQL analytics tables
```

---

## 7. Spark Bronze/Silver/Gold Refactor

The Spark analytics job was refactored into a clearer data pipeline.

### Bronze

Raw mixed event ingestion using an explicit Spark schema.

### Silver

Typed event-specific views:

* `gameStarted`
* `movePlayed`
* `gameFinished`

### Gold

Analytics tables computed from Silver data.

This replaced ad-hoc schema inference and made the pipeline more stable and explainable.

---

## 8. Explicit Spark Schema

Spark now reads the event stream with an explicit nullable schema instead of relying on schema inference.

### Why this matters

Schema inference works for small demos, but it is fragile when:

* files contain only some event types
* new fields are added later
* mixed event rows have sparse columns
* streaming and batch paths need to agree on one contract

The explicit schema makes batch and future Kafka streaming paths converge on the same event contract.

---

## 9. Data Quality Checks

Spark now computes a small data quality table for `GameFinished` events.

### Example checks

* Missing `gameId`
* Missing `tournamentId`
* Invalid `result`
* Negative `totalPly`
* Negative `durationMillis`
* Missing bot IDs
* Inconsistent winner information

The checks are reported but do not fail the Spark job yet.

---

## 10. Parquet Lake Output

An optional Parquet lake was added for Spark-native analytics storage.

### Storage roles

```text
JSONL      = event exchange / replay / assignment input
Parquet    = Spark-native historical analytics lake
PostgreSQL = serving/read model for API and UI
```

### Lake layout

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
    elo-ratings/
    data-quality/
```

Parquet output is optional and controlled through environment variables.

---

## 11. Shared Batch/Streaming Spark Pipeline

The Spark analytics pipeline now separates source acquisition from transformation logic.

### Batch path

```text
JSONL file
  -> BronzeEvents
  -> AnalyticsPipeline
  -> Gold analytics
```

### Streaming path

```text
Kafka value column
  -> KafkaEventParser
  -> SilverEvents
  -> StreamingGoldAnalytics
```

Kafka runtime is still planned separately, but the Spark-side parsing and transformation structure is ready.

---

## 12. Windowed Streaming Analytics

Streaming-compatible Spark analytics were added for future Kafka use.

### Supported windowed metrics

* Games finished per minute
* Average game duration per 5-minute window
* Bot win rate per 5-minute window
* Move throughput per minute

These methods are testable with static DataFrames and do not require a running Kafka broker.

---

## 13. Streaming Checkpoint and Replay Configuration

The streaming job now has configuration for checkpointing and replay behavior.

### Configurable values

* `KAFKA_BOOTSTRAP_SERVERS`
* `KAFKA_TOPIC`
* `SPARK_CHECKPOINT_DIR`
* `SPARK_STREAMING_STARTING_OFFSETS`
* `SPARK_STREAMING_TRIGGER_SECONDS`
* `SPARK_STREAMING_QUERY`

### Behavior

Spark stores consumed offsets and streaming state in the checkpoint directory. If the job restarts with the same checkpoint, it resumes from the stored offsets. If the checkpoint is deleted and `startingOffsets=earliest`, Spark can replay retained Kafka events from the beginning, as long as Kafka still retains them.

This gives checkpointed offset tracking and replay behavior. End-to-end exactly-once behavior still depends on the output sink.

---

## 14. Elo Rating Analytics

Batch Elo-style bot ratings were added.

### Why Elo was added

The normal leaderboard gives direct tournament score, but it does not account for opponent strength.

Elo provides an opponent-adjusted rating view.

### Model

Defaults:

* Initial rating: `1000.0`
* K factor: `32.0`

Games are processed deterministically by:

```text
timestamp, then gameId
```

### Output

Elo ratings include:

* `botId`
* `rating`
* `ratingChange`
* `gamesPlayed`
* `wins`
* `draws`
* `losses`
* `averageOpponentRating`
* `lastGameTimestamp`

Elo is available through:

* CSV output
* optional Parquet lake output
* PostgreSQL table `analytics_elo_ratings`
* analytics-service API
* Web UI Elo Ratings section

### Tradeoff

The current Elo implementation is batch-only and driver-side after collecting ordered `GameFinished` rows. This is acceptable for current tournament evaluation scale but would need a stateful or distributed design for very large streams.

---

## 15. Current Architecture Summary

```text
Bot Evaluation Arena
  -> GameRunner
  -> GameEvent JSONL
  -> Spark Analytics
      -> Bronze/Silver/Gold
      -> CSV
      -> Parquet Lake
      -> PostgreSQL Analytics Tables
  -> analytics-service
  -> Web UI /analytics
```

Kafka integration is prepared on the Spark side but will be completed later once the partner-owned Kafka runtime is ready.

---

## 16. Validation Status

Frontend validation completed:

* TypeScript check passed.
* Web UI production build passed.

Scala/SBT validation is currently blocked locally by a Windows sbt named-pipe boot lock issue before project load. This appears to be an environment/tooling issue, not a feature-specific test failure.

Manual end-to-end verification still needs to be performed:

```text
Spark -> PostgreSQL -> analytics-service -> Web UI
```

---

## 17. Next Steps

Recommended next steps:

1. Manual end-to-end verification of the analytics path.
2. Clean local sbt environment and rerun Scala tests.
3. Tournament Builder backend/job model.
4. Tournament Builder UI.
5. More bots and larger VM/server evaluation runs.
6. Kafka runtime integration when partner implementation is ready.
