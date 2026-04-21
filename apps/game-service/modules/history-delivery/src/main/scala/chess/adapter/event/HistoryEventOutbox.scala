package chess.adapter.event

import java.time.Instant

final case class HistoryOutboxEntry(
    id: Long,
    eventType: String,
    sessionId: String,
    gameId: String,
    payloadJson: String,
    createdAt: Instant,
    attempts: Int,
    lastAttemptedAt: Option[Instant],
    lastError: Option[String],
    deliveredAt: Option[Instant]
)

final case class HistoryOutboxSummary(
    totalCount: Int,
    pendingCount: Int,
    deliveredCount: Int,
    retryingCount: Int,
    oldestPendingAt: Option[Instant],
    newestPendingAt: Option[Instant],
    pendingByType: Map[String, Int]
)

trait HistoryEventOutbox:
  def append(payloadJson: String): Either[String, Long]
  def summary(): Either[String, HistoryOutboxSummary]
  def pending(limit: Int): Either[String, List[HistoryOutboxEntry]]
  def find(id: Long): Either[String, Option[HistoryOutboxEntry]]
  def markAttempted(id: Long): Either[String, Unit]
  def markDelivered(id: Long): Either[String, Unit]
  def markFailed(id: Long, error: String): Either[String, Unit]
