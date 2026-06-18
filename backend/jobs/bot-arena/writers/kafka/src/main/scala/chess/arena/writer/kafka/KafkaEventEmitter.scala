package chess.arena.writer.kafka

import chess.arena.events.{EventEmitter, GameEvent, GameEventJson}

final class KafkaEventEmitter(
    config: KafkaEventEmitterConfig = KafkaEventEmitterConfig(),
    producer: KafkaProducerClient
) extends EventEmitter with AutoCloseable:

  def this(config: KafkaEventEmitterConfig) =
    this(config, DefaultKafkaProducerClient(config))

  def this() =
    this(KafkaEventEmitterConfig())

  def emit(event: GameEvent): Unit =
    producer.send(
      topic = config.topic,
      key = event.gameId,
      value = GameEventJson.encode(event)
    )

  def flush(): Unit =
    producer.flush()

  def close(): Unit =
    producer.close()

