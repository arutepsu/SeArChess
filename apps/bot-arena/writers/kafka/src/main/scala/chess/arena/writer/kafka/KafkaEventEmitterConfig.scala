package chess.arena.writer.kafka

final case class KafkaEventEmitterConfig(
    bootstrapServers: String = "localhost:9092",
    topic: String = "game-events",
    clientId: String = "searchess-arena-kafka-emitter"
)

