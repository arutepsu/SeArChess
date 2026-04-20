package chess.adapter.event

import chess.application.event.AppEvent
import chess.application.port.event.EventPublisher
import scala.util.control.NonFatal

/** Writes terminal History-facing Game events to a durable outbox.
 *
 *  This publisher intentionally does not perform HTTP I/O. A separate
 *  [[HistoryOutboxForwarder]] drains the outbox and delivers entries to
 *  History. Outbox write failures are logged and absorbed so gameplay command
 *  success is not coupled to History infrastructure availability.
 */
class DurableHistoryEventPublisher(outbox: HistoryEventOutbox) extends EventPublisher:

  override def publish(event: AppEvent): Unit =
    if DurableHistoryEventPublisher.isTerminalBoundaryEvent(event) then
      try
        AppEventSerializer.serialize(event).foreach { json =>
          outbox.append(json).left.foreach { err =>
            System.err.println(s"[chess] History outbox write failed: $err")
          }
        }
      catch
        case NonFatal(e) =>
          System.err.println(s"[chess] History outbox write failed: ${e.getMessage}")

object DurableHistoryEventPublisher:

  def isTerminalBoundaryEvent(event: AppEvent): Boolean = event match
    case _: AppEvent.GameFinished     => true
    case _: AppEvent.GameResigned     => true
    case _: AppEvent.SessionCancelled => true
    case _                            => false
