package chess.arena.writer.kafka

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.common.serialization.StringSerializer

import java.util.Properties

final class DefaultKafkaProducerClient(config: KafkaEventEmitterConfig) extends KafkaProducerClient:
  private val producer = KafkaProducer[String, String](producerProperties(config))

  def send(topic: String, key: String, value: String): Unit =
    producer.send(ProducerRecord[String, String](topic, key, value)).get()
    ()

  def flush(): Unit =
    producer.flush()

  def close(): Unit =
    producer.flush()
    producer.close()

  private def producerProperties(config: KafkaEventEmitterConfig): Properties =
    val props = Properties()
    props.put("bootstrap.servers", config.bootstrapServers)
    props.put("client.id", config.clientId)
    props.put("key.serializer", classOf[StringSerializer].getName)
    props.put("value.serializer", classOf[StringSerializer].getName)
    props

