package chess.adapter.event

import chess.application.event.AppEvent
import chess.application.port.event.EventPublisher

/** Event publisher that silently discards every event.
  *
  * Used as the default implementation injected into services when no concrete publisher has been
  * wired up. Zero allocation, zero side effects.
  */
object NoOpEventPublisher extends EventPublisher:
  override def publish(event: AppEvent): Unit = ()
