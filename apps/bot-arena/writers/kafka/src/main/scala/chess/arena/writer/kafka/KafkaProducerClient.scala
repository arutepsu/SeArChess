package chess.arena.writer.kafka

trait KafkaProducerClient extends AutoCloseable:
  def send(topic: String, key: String, value: String): Unit
  def flush(): Unit

