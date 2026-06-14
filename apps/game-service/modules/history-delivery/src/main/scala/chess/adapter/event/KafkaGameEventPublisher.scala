package chess.adapter.event

import chess.adapter.event.kafka.{EventEnvelope, GameEventEnvelope, KafkaTopics}
import chess.application.event.AppEvent
import chess.application.port.event.EventPublisher
import chess.observability.StructuredLog
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.kafka.ProducerSettings
import org.apache.pekko.kafka.scaladsl.Producer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.kafka.common.serialization.StringSerializer

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

final class KafkaGameEventPublisher(
    bootstrapServers: String,
    topic: String = KafkaTopics.GameEvents
)(using system: ActorSystem)
    extends EventPublisher:

  private given ExecutionContext = system.dispatcher
  private val settings =
    ProducerSettings(system, new StringSerializer, new StringSerializer)
      .withBootstrapServers(bootstrapServers)

  override def publish(event: AppEvent): Unit =
    GameEventEnvelope.fromAppEvent(event).foreach { envelope =>
      val key    = event.gameId.value.toString
      val json   = EventEnvelope.writePayloadEnvelope(envelope)
      val record = ProducerRecord[String, String](topic, key, json)
      Source
        .single(record)
        .runWith(Producer.plainSink(settings))
        .onComplete {
          case Success(Done) =>
            StructuredLog.info(
              "game-service",
              "kafka_game_event_published",
              "topic" -> topic,
              "key" -> key,
              "eventId" -> envelope.eventId,
              "eventType" -> envelope.eventType,
              "correlationId" -> envelope.correlationId,
              "gameId" -> key
            )
          case Failure(e) =>
            StructuredLog.warn(
              "game-service",
              "kafka_game_event_publish_failed",
              "topic" -> topic,
              "key" -> key,
              "eventId" -> envelope.eventId,
              "eventType" -> envelope.eventType,
              "error" -> Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
            )
        }
    }
