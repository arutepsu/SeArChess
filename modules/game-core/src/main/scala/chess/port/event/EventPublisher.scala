package chess.application.port.event

import chess.application.event.AppEvent

/** Outbound port for application-layer event publication.
 *
 *  Services call [[publish]] after a meaningful state transition completes.
 *  The port is fire-and-forget: it returns [[Unit]] and must not throw; any
 *  implementation failure should be handled internally (e.g. logged and
 *  silently dropped) so that event publication never breaks a service call.
 *
 *  === What belongs behind this port ===
 *  - In-memory collecting (tests, development)
 *  - Structured logging
 *  - Future WebSocket bridging
 *  - Future message broker publication
 *
 *  === What does NOT belong behind this port ===
 *  - Subscription / consumer registration
 *  - Streaming or reactive pipeline semantics
 *  - Broker acknowledgement / backpressure
 *
 *  All of those are future adapter concerns; this port stays minimal.
 *
 *  Implementations belong in `chess.adapter.*`; the application layer depends
 *  only on this trait.
 */
trait EventPublisher:

  /** Publish a single [[AppEvent]].
   *
   *  Must not throw.  The caller does not know or care what happens next;
   *  publication is a side-channel concern.
   */
  def publish(event: AppEvent): Unit
