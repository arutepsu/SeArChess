# Kafka Spark Streaming Integration

## Purpose

Phase 6A adds a streaming path beside the existing JSONL path:

```
TournamentRunner
  -> EventEmitter
  -> KafkaEventEmitter
  -> Kafka topic: game-events
  -> Spark Structured Streaming
  -> console analytics
```

JSONL came first because it is deterministic, easy to inspect, and does not require local infrastructure. Kafka now reuses the same `GameEvent` JSON contract so the live path can be tested without changing the tournament engine or analytics event schema.

## Event Contract

Kafka is only a transport for the existing arena event JSON.

- Topic: `game-events` by default
- Key: `event.gameId`
- Value: `GameEventJson.encode(event)`, the same JSON payload written to JSONL
- Default bootstrap servers: `localhost:9092`
- Default producer client id: `searchess-arena-kafka-emitter`

`GameRunner` and `TournamentRunner` still emit through `EventEmitter` only. They do not depend on Kafka.

## Kafka EventEmitter

`arenaWriterKafka` provides:

- `KafkaEventEmitterConfig`
- `KafkaProducerClient`
- `DefaultKafkaProducerClient`
- `KafkaEventEmitter`
- `CompositeEventEmitter`

`KafkaEventEmitter` serializes every `GameEvent` with `GameEventJson.encode`, publishes it to the configured topic, and uses `gameId` as the Kafka record key.

`CompositeEventEmitter(List(...))` forwards each event to every child emitter. Phase 6A uses fail-fast behavior: if one child emitter throws, later emitters in the list may not receive that event.

## Kafka Demo

The Kafka demo runs a small sequential heuristic tournament only. It does not include Stockfish.

Defaults:

- bootstrap servers: `localhost:9092`
- topic: `game-events`
- repetitions: `1`
- max ply per game: `100`

Command:

```
sbt demoKafkaHeuristicTournament
```

Equivalent explicit command:

```
sbt "arenaDemoKafka/run [bootstrapServers] [topic] [optionalJsonlPath] [repetitions] [maxPlyPerGame]"
```

Environment variables are also supported:

```
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
KAFKA_GAME_EVENTS_TOPIC=game-events
```

Example with JSONL dual-write:

```
sbt "arenaDemoKafka/run localhost:9092 game-events target/arena/kafka-heuristic-tournament/game-events.jsonl"
```

## Spark Streaming Analytics

`GameAnalyticsStreamingJob` reads Kafka values as strings, parses the existing JSON fields, filters `eventType == GameFinished`, and writes simple live aggregations to console sinks.

Minimum live analytics:

- finished game count by `winnerBotId`, with draws grouped as `draw`
- finished game count by `result`
- finished game count by `terminationReason`

Command:

```
sbt demoSparkStreamingAnalytics
```

Equivalent explicit command:

```
sbt "sparkAnalytics/runMain chess.analytics.GameAnalyticsStreamingJob [bootstrapServers] [topic] [checkpointLocation]"
```

Defaults:

```
bootstrapServers = localhost:9092
topic = game-events
checkpointLocation = target/spark-streaming-checkpoints/game-events
```

Spark analytics reads Kafka JSON values only. It does not depend on `BotPlayer`, `GameRunner`, UCI, or Stockfish classes.

## Local Smoke Flow

Phase 6B requires a real Kafka broker. Use one of these options:

- teammate-owned Kafka cluster reachable from your machine
- existing project deployment that exposes Kafka on a host/port
- local Kafka installation
- local Docker container

Docker option using the official Apache Kafka image:

```
docker run -d --name searchess-kafka -p 9092:9092 apache/kafka:latest
```

Check that the broker port is open:

```
# Windows PowerShell
Test-NetConnection -ComputerName localhost -Port 9092

# Linux/macOS
nc -vz localhost 9092
```

Optional topic creation from inside the container:

```
docker exec --workdir /opt/kafka/bin searchess-kafka `
  ./kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic game-events
```

When finished:

```
docker rm -f searchess-kafka
```

If a local or teammate-owned Kafka broker is available:

```
sbt testArenaWriterKafka
sbt demoSparkStreamingAnalytics
```

In another terminal:

```
sbt demoKafkaHeuristicTournament
```

With custom broker details:

```
# Windows PowerShell
$env:KAFKA_BOOTSTRAP_SERVERS="localhost:9092"
$env:KAFKA_GAME_EVENTS_TOPIC="game-events"
sbt demoSparkStreamingAnalytics
sbt demoKafkaHeuristicTournament
```

```
# Linux/macOS
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export KAFKA_GAME_EVENTS_TOPIC=game-events
sbt demoSparkStreamingAnalytics
sbt demoKafkaHeuristicTournament
```

The streaming job is long-running. Stop it with Ctrl+C when done.

## Tests

Normal unit tests do not require a Kafka broker:

```
sbt testArenaWriterKafka
```

They verify:

- `KafkaEventEmitter` uses `gameId` as the Kafka key
- `KafkaEventEmitter` serializes values with `GameEventJson.encode`
- `CompositeEventEmitter` forwards to all child emitters
- Kafka config defaults are stable

Real broker integration tests are intentionally not required in normal CI for Phase 6A. If a broker is available, use the manual smoke flow above.

## Out Of Scope

This phase does not add SearchessAI adapters, LCZero, Lichess import, database persistence, dashboard/UI work, parallel game execution, schema registry, Avro, Protobuf, or a Spark version upgrade.
