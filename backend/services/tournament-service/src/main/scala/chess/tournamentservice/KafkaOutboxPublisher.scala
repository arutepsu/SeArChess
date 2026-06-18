package chess.tournamentservice

import cats.effect.IO
import chess.observability.StructuredLog
import chess.tournamentservice.db.OutboxEvent

final class KafkaOutboxPublisher(sender: KafkaRecordSender, topic: String) extends OutboxPublisher:

  def publish(event: OutboxEvent): IO[Unit] =
    IO.fromEither(
      KafkaEnvelopeBuilder.build(event).left.map(msg => RuntimeException(msg))
    ).flatMap { envelope =>
      sender.send(topic, event.aggregateId, envelope) >>
      IO(StructuredLog.info(
        "tournament-service",
        "kafka_event_sent",
        "eventId"       -> event.eventId,
        "eventType"     -> event.eventType,
        "aggregateType" -> event.aggregateType,
        "aggregateId"   -> event.aggregateId,
        "topic"         -> topic
      ))
    }
