package chess.adapter.event

import chess.application.event.AppEvent
import chess.application.port.event.EventPublisher
import chess.observability.StructuredLog
import scala.util.control.NonFatal

/** Writes terminal History-facing Game events to a durable outbox.
  *
  * This publisher intentionally does not perform HTTP I/O. A separate [[HistoryOutboxForwarder]]
  * drains the outbox and delivers entries to History. Outbox write failures are logged and absorbed
  * so gameplay command success is not coupled to History infrastructure availability.
  */
class DurableHistoryEventPublisher(outbox: HistoryEventOutbox) extends EventPublisher:

  override def publish(event: AppEvent): Unit =
    if DurableHistoryEventPublisher.isTerminalBoundaryEvent(event) then
      try
        AppEventSerializer.serialize(event).foreach { json =>
          outbox.append(json).left.foreach { err =>
            StructuredLog.warn(
              "game-service",
              "history_outbox_write_failed",
              "eventType" -> DurableHistoryEventPublisher.eventName(event),
              "error" -> err
            )
          }
        }
      catch
        case NonFatal(e) =>
          StructuredLog.warn(
            "game-service",
            "history_outbox_write_failed",
            "eventType" -> DurableHistoryEventPublisher.eventName(event),
            "error" -> e.getMessage
          )

object DurableHistoryEventPublisher:

  def isTerminalBoundaryEvent(event: AppEvent): Boolean = event match
    case _: AppEvent.GameFinished     => true
    case _: AppEvent.GameResigned     => true
    case _: AppEvent.SessionCancelled => true
    case _                            => false

  def eventName(event: AppEvent): String = event match
    case _: AppEvent.GameFinished            => "GameFinished"
    case _: AppEvent.GameResigned            => "GameResigned"
    case _: AppEvent.SessionCancelled        => "SessionCancelled"
    case _: AppEvent.SessionCreated          => "SessionCreated"
    case _: AppEvent.MoveApplied             => "MoveApplied"
    case _: AppEvent.MoveRejected            => "MoveRejected"
    case _: AppEvent.PromotionPending        => "PromotionPending"
    case _: AppEvent.SessionLifecycleChanged => "SessionLifecycleChanged"
    case _: AppEvent.AITurnRequested         => "AITurnRequested"
    case _: AppEvent.AITurnCompleted         => "AITurnCompleted"
    case _: AppEvent.AITurnFailed            => "AITurnFailed"
