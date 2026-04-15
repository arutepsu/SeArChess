package chess.startup.assembly

import chess.adapter.event.FanOutEventPublisher
import chess.adapter.websocket.{ChessWebSocketServer, WebSocketConnectionRegistry, WebSocketEventPublisher}
import chess.application.port.event.EventPublisher
import chess.config.{AppConfig, EventMode}

/** Assembled event distribution infrastructure produced by [[EventAssembly.assemble]].
 *
 *  @param publisher ready-to-inject [[EventPublisher]] for application services;
 *                   delivers events to all wired consumers
 *  @param wsServer  live WebSocket server handle, present when WebSocket is enabled
 *                   in config; call `stop(0)` on the contained value to shut down
 */
final case class EventWiring(
  publisher: EventPublisher,
  wsServer:  Option[ChessWebSocketServer]
)

/** Assembles the event distribution layer from [[AppConfig]].
 *
 *  This object is the single place in the composition root that decides how
 *  [[chess.application.event.AppEvent]]s are delivered to consumers.  Adding
 *  a new distribution strategy (e.g. Kafka) means adding a new branch in
 *  [[assemble]] and a corresponding [[EventMode]] variant — nothing else
 *  changes in the bootstrap or application layers.
 *
 *  === Current strategies ===
 *  - [[EventMode.InProcess]]: fan-out within the same JVM.  WebSocket is
 *    attached as an optional consumer when [[chess.config.WebSocketConfig.enabled]]
 *    is true; when disabled, the publisher becomes a silent no-op.
 *
 *  === Extension path ===
 *  Future strategies (e.g. `EventMode.Kafka`) would:
 *  1. Add a new `case` to [[EventMode]].
 *  2. Add a new `case` branch in [[assemble]].
 *  3. Implement the corresponding private assembly method here.
 *  Application services, routes, and GUI/TUI adapters are unaffected.
 */
object EventAssembly:

  /** Assemble event distribution infrastructure according to `config.eventMode`.
   *
   *  Delegates to the strategy-specific assembly method.  The returned
   *  [[EventWiring]] carries the publisher to inject into application services
   *  and the optional server handle for shutdown.
   */
  def assemble(config: AppConfig): EventWiring =
    config.eventMode match
      case EventMode.InProcess => assembleInProcess(config)

  // ── Strategy: InProcess ─────────────────────────────────────────────────────

  /** In-process fan-out: events are delivered synchronously to all registered
   *  consumers within the same JVM.
   *
   *  When WebSocket is enabled, a [[WebSocketEventPublisher]] is wired as a
   *  consumer so that clients receive push notifications after each game event.
   *  When WebSocket is disabled, [[FanOutEventPublisher]] receives no publishers
   *  and silently discards all events — correct when there are no push clients.
   */
  private def assembleInProcess(config: AppConfig): EventWiring =
    if config.webSocket.enabled then
      // Registry is shared: ChessWebSocketServer registers/unregisters live
      // connections; WebSocketEventPublisher routes AppEvents to those connections.
      val registry  = WebSocketConnectionRegistry()
      val publisher = WebSocketEventPublisher(registry)
      val server    = ChessWebSocketServer(port = config.webSocket.port, registry)
      server.start()
      EventWiring(FanOutEventPublisher(publisher), Some(server))
    else
      EventWiring(FanOutEventPublisher(), None)
