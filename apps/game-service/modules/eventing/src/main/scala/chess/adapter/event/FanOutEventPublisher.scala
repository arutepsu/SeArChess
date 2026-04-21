package chess.adapter.event

import chess.application.event.AppEvent
import chess.application.port.event.EventPublisher

/** [[EventPublisher]] adapter that delivers each event to every downstream publisher in sequence.
  *
  * A failure (thrown exception) in one downstream publisher is silently absorbed so that subsequent
  * publishers always receive the event. Per the [[EventPublisher]] contract individual publishers
  * must not throw; the try-catch here is a defensive belt-and-suspenders guard.
  *
  * ===Typical use===
  * Wire multiple concrete publishers at the composition root without letting each publisher know
  * about the others:
  * {{{
  *    FanOutEventPublisher(webSocketPublisher, loggingPublisher)
  * }}}
  *
  * @param publishers
  *   ordered sequence of downstream publishers to fan out to
  */
class FanOutEventPublisher(publishers: EventPublisher*) extends EventPublisher:

  override def publish(event: AppEvent): Unit =
    publishers.foreach { p =>
      try p.publish(event)
      catch case _: Exception => () // absorb per-publisher failures; delivery continues
    }
