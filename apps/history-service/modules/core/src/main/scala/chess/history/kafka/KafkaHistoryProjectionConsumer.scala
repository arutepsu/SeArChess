package chess.history.kafka

import chess.adapter.event.kafka.{
  DeadLetterMessage,
  EventEnvelope,
  FailureCategory,
  GameEventEnvelope,
  KafkaTopics
}
import chess.history.{ArchiveRecord, HistoryIngestionError}
import chess.observability.{StructuredLog, TraceReporter}
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.kafka.{ConsumerSettings, ProducerSettings, Subscriptions}
import org.apache.pekko.kafka.ConsumerMessage.CommittableMessage
import org.apache.pekko.kafka.scaladsl.{Consumer, Producer}
import org.apache.pekko.stream.KillSwitches
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{FiniteDuration, MICROSECONDS}

final class KafkaHistoryProjectionConsumer(
    bootstrapServers: String,
    ingest: String => Either[HistoryIngestionError, ArchiveRecord],
    groupId: String = KafkaHistoryProjectionConsumer.DefaultGroupId,
    topic: String = KafkaTopics.GameEvents
)(using system: ActorSystem):

  private given ExecutionContext = system.dispatcher

  private val consumerSettings =
    ConsumerSettings(system, new StringDeserializer, new StringDeserializer)
      .withBootstrapServers(bootstrapServers)
      .withGroupId(groupId)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

  private val producerSettings =
    ProducerSettings(system, new StringSerializer, new StringSerializer)
      .withBootstrapServers(bootstrapServers)

  private val (killSwitch, done) =
    Consumer
      .committableSource(consumerSettings, Subscriptions.topics(topic))
      .viaMat(KillSwitches.single)(Keep.right)
      .mapAsync(1)(processMessage)
      .toMat(Sink.ignore)(Keep.both)
      .run()

  def stop(): Future[Done] =
    killSwitch.shutdown()
    done

  private def processMessage(message: CommittableMessage[String, String]): Future[Done] =
    val startNs = System.nanoTime()
    val metadata = message.record
    val payload  = metadata.value()
    EventEnvelope.readPayloadEnvelope(payload).flatMap(GameEventEnvelope.validate) match
      case Left(error) =>
        publishDlq(message, None, None, FailureCategory.NonRetryable, error)
          .flatMap(_ => message.committableOffset.commitScaladsl())
      case Right(envelope) =>
        project(envelope) match
          case Right(_) =>
            val durationMs = (System.nanoTime() - startNs).toDouble / 1e6
            TraceReporter.emit(
              "history-service",
              s"kafka consume ${envelope.eventType}",
              envelope.correlationId,
              FiniteDuration(math.max(1L, (durationMs * 1000).toLong), MICROSECONDS),
              "messaging.system" -> "kafka",
              "messaging.destination.name" -> metadata.topic,
              "messaging.operation" -> "process",
              "kafka.partition" -> metadata.partition,
              "kafka.offset" -> metadata.offset,
              "consumer.group" -> groupId,
              "event.id" -> envelope.eventId,
              "event.type" -> envelope.eventType,
              "game.id" -> envelope.aggregateId
            )
            StructuredLog.info(
              "history-service",
              "kafka_history_projection_processed",
              "topic" -> metadata.topic,
              "partition" -> metadata.partition,
              "offset" -> metadata.offset,
              "eventId" -> envelope.eventId,
              "eventType" -> envelope.eventType,
              "correlationId" -> envelope.correlationId,
              "gameId" -> envelope.aggregateId
            )
            message.committableOffset.commitScaladsl()
          case Left(error) if isRetryable(error) =>
            publishRetry(message, envelope, KafkaTopics.GameEventsRetry10s, error.toString)
              .flatMap(_ => message.committableOffset.commitScaladsl())
          case Left(error) =>
            publishDlq(message, Some(envelope.eventId), Some(envelope.eventType), FailureCategory.NonRetryable, error.toString)
              .flatMap(_ => message.committableOffset.commitScaladsl())

  private def project(envelope: EventEnvelope[ujson.Value]): Either[HistoryIngestionError, Unit] =
    val startNs = System.nanoTime()
    val result =
      envelope.eventType match
        case GameEventEnvelope.GameFinished =>
          ingest(ujson.write(envelope.payload)).map(_ => ())
        case GameEventEnvelope.GameCreated | GameEventEnvelope.MoveApplied | GameEventEnvelope.MoveRejected =>
          Right(())
        case other =>
          Left(HistoryIngestionError.InvalidEvent(s"unsupported event type: $other"))
    val durationMs = (System.nanoTime() - startNs).toDouble / 1e6
    TraceReporter.emit(
      "history-service",
      "MongoDB update",
      envelope.correlationId,
      FiniteDuration(math.max(1L, (durationMs * 1000).toLong), MICROSECONDS),
      "db.system" -> "mongodb",
      "db.operation" -> "projection",
      "event.id" -> envelope.eventId,
      "event.type" -> envelope.eventType,
      "game.id" -> envelope.aggregateId,
      "projection.result" -> result.fold(_ => "failed", _ => "projected")
    )
    result

  private def isRetryable(error: HistoryIngestionError): Boolean =
    error match
      case HistoryIngestionError.ArchiveFetchFailed(_) => true
      case HistoryIngestionError.PersistenceFailed(_)  => true
      case HistoryIngestionError.MaterializationFailed(_) => true
      case HistoryIngestionError.InvalidEvent(_)       => false

  private def publishRetry(
      message: CommittableMessage[String, String],
      envelope: EventEnvelope[ujson.Value],
      retryTopic: String,
      reason: String
  ): Future[Done] =
    StructuredLog.warn(
      "history-service",
      "kafka_history_projection_retry",
      "retryTopic" -> retryTopic,
      "eventId" -> envelope.eventId,
      "eventType" -> envelope.eventType,
      "gameId" -> envelope.aggregateId,
      "reason" -> reason
    )
    Source
      .single(ProducerRecord[String, String](retryTopic, message.record.key(), message.record.value()))
      .runWith(Producer.plainSink(producerSettings))

  private def publishDlq(
      message: CommittableMessage[String, String],
      eventId: Option[String],
      eventType: Option[String],
      category: FailureCategory,
      reason: String
  ): Future[Done] =
    val record = message.record
    val dlq = DeadLetterMessage(
      originalTopic = record.topic,
      originalPartition = record.partition,
      originalOffset = record.offset,
      eventId = eventId,
      eventType = eventType,
      consumerGroup = groupId,
      failureCategory = category,
      failureReason = reason,
      failedAt = Instant.now(),
      originalPayload = record.value()
    )
    StructuredLog.warn(
      "history-service",
      "kafka_history_projection_dlq",
      "eventId" -> eventId,
      "eventType" -> eventType,
      "reason" -> reason
    )
    Source
      .single(ProducerRecord[String, String](KafkaTopics.GameEventsDlq, record.key(), DeadLetterMessage.write(dlq)))
      .runWith(Producer.plainSink(producerSettings))

object KafkaHistoryProjectionConsumer:
  val DefaultGroupId: String = "history-service-game-projector"
