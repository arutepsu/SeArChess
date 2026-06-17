package chess.adapter.event

import chess.adapter.event.kafka.{EventEnvelope, GameEventEnvelope, KafkaTopics}
import chess.application.event.AppEvent
import chess.application.port.event.EventPublisher
import chess.observability.{CorrelationContext, StructuredLog, TraceReporter}
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.kafka.ProducerSettings
import org.apache.pekko.kafka.scaladsl.Producer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.kafka.common.serialization.StringSerializer

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{FiniteDuration, MICROSECONDS}
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
    GameEventEnvelope.fromAppEvent(event, correlationId = CorrelationContext.current).foreach { envelope =>
      val key    = event.gameId.value.toString
      val json   = EventEnvelope.writePayloadEnvelope(envelope)
      val record = ProducerRecord[String, String](topic, key, json)
      val startNs = System.nanoTime()
      Source
        .single(record)
        .runWith(Producer.plainSink(settings))
        .onComplete {
          case Success(Done) =>
            val durationMs = (System.nanoTime() - startNs).toDouble / 1e6
            TraceReporter.emit(
              "game-service",
              s"kafka produce ${envelope.eventType}",
              envelope.correlationId,
              FiniteDuration(math.max(1L, (durationMs * 1000).toLong), MICROSECONDS),
              "messaging.system" -> "kafka",
              "messaging.destination.name" -> topic,
              "messaging.operation" -> "publish",
              "kafka.key" -> key,
              "event.id" -> envelope.eventId,
              "event.type" -> envelope.eventType,
              "game.id" -> key
            )
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
