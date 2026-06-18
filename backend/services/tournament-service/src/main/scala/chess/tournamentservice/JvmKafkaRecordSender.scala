package chess.tournamentservice

import cats.effect.IO
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.kafka.common.serialization.StringSerializer

import java.util.Properties

final class JvmKafkaRecordSender(producer: KafkaProducer[String, String]) extends KafkaRecordSender:

  def send(topic: String, key: String, value: String): IO[Unit] =
    IO.blocking(producer.send(new ProducerRecord[String, String](topic, key, value)).get()).void

  def close(): IO[Unit] =
    IO.blocking(producer.close())

object JvmKafkaRecordSender:

  def create(bootstrapServers: String, clientId: String, acks: String): JvmKafkaRecordSender =
    val props = new Properties()
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
    props.put(ProducerConfig.CLIENT_ID_CONFIG, clientId)
    props.put(ProducerConfig.ACKS_CONFIG, acks)
    new JvmKafkaRecordSender(new KafkaProducer[String, String](props))
