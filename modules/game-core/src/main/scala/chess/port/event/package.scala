/** Outbound port: event publication abstraction.
 *
 *  Responsibilities:
 *  - interface definition for publishing application-layer events externally
 *    (e.g. EventPublisher, EventBus)
 *  - no implementation — implementations live in chess.adapter.* packages
 *    (in-memory, message broker, WebSocket push, etc.)
 *
 *  Example interface to introduce when external event publication is needed:
 *  {{{
 *  trait EventPublisher[E]:
 *    def publish(event: E): Unit
 *  }}}
 *
 *  Not yet populated.
 */
package chess.application.port.event
