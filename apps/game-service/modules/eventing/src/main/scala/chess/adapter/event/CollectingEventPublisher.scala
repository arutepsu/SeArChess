package chess.adapter.event

import chess.application.event.AppEvent
import chess.application.port.event.EventPublisher
import scala.collection.mutable

/** Event publisher that accumulates published events in an in-memory buffer.
 *
 *  Intended for tests and development environments where published events need
 *  to be inspected after the fact.
 *
 *  Not thread-safe.  Create one instance per test or per logical operation
 *  under test; do not share across concurrent calls.
 */
class CollectingEventPublisher extends EventPublisher:

  private val buffer = mutable.ArrayBuffer.empty[AppEvent]

  override def publish(event: AppEvent): Unit =
    buffer += event

  /** All events published so far, in publication order. */
  def events: List[AppEvent] = buffer.toList

  /** Remove all collected events.  Useful between phases of a multi-step test. */
  def clear(): Unit = buffer.clear()
