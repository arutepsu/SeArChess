package chess.tournamentservice.db

import java.time.Instant
import java.util.UUID

case class OutboxEvent(
    eventId: String,
    aggregateType: String,
    aggregateId: String,
    eventType: String,
    payloadJson: String,
    status: String,
    createdAt: Instant,
    publishedAt: Option[Instant],
    lastError: Option[String],
    attemptCount: Int
)

object OutboxEvent:
  def pending(
      aggregateType: String,
      aggregateId: String,
      eventType: String,
      payloadJson: String,
      createdAt: Instant
  ): OutboxEvent =
    OutboxEvent(
      eventId       = UUID.randomUUID().toString,
      aggregateType = aggregateType,
      aggregateId   = aggregateId,
      eventType     = eventType,
      payloadJson   = payloadJson,
      status        = "pending",
      createdAt     = createdAt,
      publishedAt   = None,
      lastError     = None,
      attemptCount  = 0
    )
