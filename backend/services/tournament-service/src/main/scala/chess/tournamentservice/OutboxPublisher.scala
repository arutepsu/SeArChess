package chess.tournamentservice

import cats.effect.IO
import chess.observability.StructuredLog
import chess.tournamentservice.db.OutboxEvent

trait OutboxPublisher:
  def publish(event: OutboxEvent): IO[Unit]

object LoggingOutboxPublisher extends OutboxPublisher:
  def publish(event: OutboxEvent): IO[Unit] =
    IO(StructuredLog.info(
      "tournament-service",
      "outbox_event_published",
      "eventId"       -> event.eventId,
      "eventType"     -> event.eventType,
      "aggregateType" -> event.aggregateType,
      "aggregateId"   -> event.aggregateId
    ))

object NoOpOutboxPublisher extends OutboxPublisher:
  def publish(event: OutboxEvent): IO[Unit] = IO.unit
