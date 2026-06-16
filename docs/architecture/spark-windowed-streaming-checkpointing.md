# Spark Windowed Streaming And Checkpointing

Phase 9D prepares Spark analytics for real Kafka streaming without requiring Kafka in tests.

## Why Windowed Analytics

The batch analytics answer questions after a tournament file exists. Streaming analytics need time-bounded views while events are arriving, such as games finished per minute, average game duration over recent windows, bot win rate in a window, and move throughput.

## Available Streaming Queries

`SPARK_STREAMING_QUERY` selects one console query at a time:

- `leaderboard`
- `terminations`
- `bot-families`
- `games-per-minute`
- `avg-duration-window`
- `win-rate-window`
- `move-throughput`

The windowed metrics are implemented in `WindowedStreamingAnalytics`:

- `gamesFinishedPerMinute`: 1-minute GameFinished count.
- `avgGameDurationPerWindow`: 5-minute GameFinished count and average duration.
- `botWinRatePerWindow`: 5-minute games, wins, draws, losses, and win rate per bot.
- `moveThroughputPerMinute`: 1-minute MovePlayed count.

## Configuration

| Variable | Default | Notes |
| --- | --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka bootstrap servers for the future runtime. |
| `KAFKA_TOPIC` | `game-events` | Kafka topic. The legacy `KAFKA_GAME_EVENTS_TOPIC` is also accepted as a fallback. |
| `SPARK_CHECKPOINT_DIR` | `target/spark-checkpoints/game-analytics` | Root checkpoint directory. |
| `SPARK_STREAMING_STARTING_OFFSETS` | `latest` | Allowed values are `latest` and `earliest`. |
| `SPARK_STREAMING_TRIGGER_SECONDS` | `5` | Positive processing-time trigger interval. |
| `SPARK_STREAMING_QUERY` | `leaderboard` | Selects the streaming output query. |

Each query writes checkpoints under a subfolder such as:

```text
target/spark-checkpoints/game-analytics/games-per-minute
```

## Checkpoint And Replay Behavior

Spark stores checkpointed offset tracking and query progress in `checkpointLocation`.

If `SPARK_STREAMING_STARTING_OFFSETS=earliest` and the checkpoint directory does not exist, Spark can replay retained Kafka events from the beginning of the topic. If the checkpoint directory exists, Spark resumes from checkpointed offsets instead of applying `startingOffsets` again.

To force a replay, stop the query and delete the relevant checkpoint subfolder. Replay is only possible while Kafka still retains the events.

Exactly-once-style processing depends on sink semantics. The current console sink is for development visibility, not an end-to-end exactly-once serving path.

## Future Usage

PowerShell:

```powershell
$env:KAFKA_BOOTSTRAP_SERVERS="localhost:9092"
$env:KAFKA_TOPIC="game-events"
$env:SPARK_CHECKPOINT_DIR="target/spark-checkpoints/game-analytics"
$env:SPARK_STREAMING_STARTING_OFFSETS="earliest"
$env:SPARK_STREAMING_QUERY="games-per-minute"
sbt demoKafkaSparkStreaming
```

## Current Limitations

- Phase 9D does not require or start a real Kafka broker.
- Streaming tests use static DataFrames.
- The console sink is a development sink.
- Kafka retention bounds replay.
- Existing batch CSV, PostgreSQL, Parquet lake, analytics-service API, Web UI, arena, and bot behavior are unchanged.
