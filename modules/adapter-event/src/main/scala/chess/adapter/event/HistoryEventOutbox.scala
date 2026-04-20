package chess.adapter.event

import java.time.Instant

final case class HistoryOutboxEntry(
  id:          Long,
  eventType:   String,
  sessionId:   String,
  gameId:      String,
  payloadJson: String,
  createdAt:   Instant,
  attempts:    Int,
  lastError:   Option[String],
  deliveredAt: Option[Instant]
)

trait HistoryEventOutbox:
  def append(payloadJson: String): Either[String, Long]
  def pending(limit: Int): Either[String, List[HistoryOutboxEntry]]
  def markDelivered(id: Long): Either[String, Unit]
  def markFailed(id: Long, error: String): Either[String, Unit]
