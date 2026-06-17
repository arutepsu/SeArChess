# Game-Service Kafka → Spark Connection

## Context

This note covers the **game-service production Kafka flow** — distinct from the
bot-arena demo flow described in `kafka-spark-streaming-integration.md`.

The game-service publishes live game events to Kafka as games progress.
Spark Structured Streaming can consume those events and write derived analytics
to Postgres so `analytics-service` and the web-ui `/analytics` page can display them.

## Topic and Schema

- **Topic**: `searchess.game.events.v1`
- **Key**: `gameId` (UUID string)
- **Value**: JSON `EventEnvelope` (version 1, producer `game-service`)

```json
{
  "eventId":       "uuid",
  "eventType":     "GameCreated|MoveApplied|MoveRejected|GameFinished",
  "eventVersion":  1,
  "occurredAt":    "2026-06-16T21:11:20Z",
  "producer":      "game-service",
  "correlationId": "uuid",
  "causationId":   null,
  "aggregateType": "Game",
  "aggregateId":   "game-uuid",
  "payload": {
    "type":       "game.finished.v1",
    "sessionId":  "uuid",
    "gameId":     "uuid",
    "result":     "Checkmate|Draw",
    "winner":     "White|Black|null",
    "drawReason": "Stalemate|null"
  }
}
```

## Spark Bootstrap Address

| Environment | Value |
|---|---|
| K8s / uni-server (Spark as pod in `searchess` ns) | `kafka:9092` |
| K8s / uni-server (Spark from outside cluster) | `kubectl port-forward svc/kafka 9092:9092 -n searchess` → `localhost:9092` |
| Docker Compose local dev | Kafka is not host-exposed by default; add `ports: - "9092:9092"` to the kafka service and a `PLAINTEXT_HOST` advertised listener |

## Spark Job Sketch

```scala
val raw = spark.readStream
  .format("kafka")
  .option("kafka.bootstrap.servers", "kafka:9092")
  .option("subscribe", "searchess.game.events.v1")
  .option("startingOffsets", "earliest")
  .load()

val finished = raw
  .selectExpr("CAST(value AS STRING) AS json")
  .select(from_json(col("json"), envelopeSchema).as("e"))
  .filter(col("e.eventType") === "GameFinished")
  .select(
    col("e.aggregateId").as("game_id"),
    col("e.occurredAt").as("occurred_at"),
    col("e.payload.result").as("result"),
    col("e.payload.winner").as("winner"),
    col("e.payload.drawReason").as("draw_reason")
  )

finished.writeStream
  .outputMode("append")
  .foreachBatch { (batch, _) =>
    batch.write.mode("append")
      .jdbc(postgresUrl, "public.game_analytics", jdbcProps)
  }
  .option("checkpointLocation", "/tmp/spark-checkpoints/searchess-game-events")
  .start()
  .awaitTermination()
```

## Event Filter

Only `GameFinished` events carry result data. Filter on `eventType == "GameFinished"`.
`GameCreated`, `MoveApplied`, and `MoveRejected` events are emitted but are not
relevant for end-of-game analytics.

## Recommended Sink

**PostgreSQL `public` schema** — the same schema that `analytics-service` reads from.

Reason: `analytics-service` already exposes `/api/analytics/` and the web-ui
already has an `/analytics` route consuming it. Writing Spark output to the `public`
schema makes analytics visible in the browser immediately with no new infrastructure.

This mirrors the pattern already used by `tournament-service` + `sparkAnalytics`
(see `Dockerfile.tournament` and `tournament-analytics-execution.md`).

## Checkpoint

Use a stable path backed by a volume. In K8s, mount a PVC and use e.g.
`/spark-checkpoints/searchess-game-events`. In Docker Compose or local,
`/tmp/spark-checkpoints/searchess-game-events` is sufficient for a demo.

## Known Gap

The Kafka broker in Docker Compose is on the internal Docker network only.
Spark running on the host cannot reach `kafka:9092` without a host port mapping.
See the bootstrap address table above for the fix required before running Spark locally.
