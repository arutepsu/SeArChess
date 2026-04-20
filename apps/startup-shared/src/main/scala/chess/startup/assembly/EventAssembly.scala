package chess.startup.assembly

import chess.adapter.event.{AppEventSerializer, FanOutEventPublisher, HistoryHttpEventPublisher, HistoryOutboxForwarder, SqliteHistoryEventOutbox}
import chess.adapter.websocket.{ChessWebSocketServer, WebSocketConnectionRegistry, WebSocketEventPublisher}
import chess.application.port.event.{EventPublisher, NoOpTerminalEventJsonSerializer, TerminalEventJsonSerializer}
import chess.config.{AppConfig, EventMode, PersistenceMode}

/** Assembled event distribution infrastructure produced by [[EventAssembly.assemble]].
 *
 *  @param publisher          ready-to-inject [[EventPublisher]] for application services;
 *                            delivers events to all wired consumers
 *  @param wsServer           live WebSocket server handle, present when WebSocket is enabled
 *                            in config; call `stop(0)` on the contained value to shut down
 *  @param shutdown           cleanup hook: stops the outbox forwarder and closes the outbox
 *                            connection when history forwarding is enabled
 *  @param terminalSerializer serialiser injected into application services for transactional
 *                            outbox writes; [[NoOpTerminalEventJsonSerializer]] when no durable
 *                            outbox is configured
 */
final case class EventWiring(
  publisher:          EventPublisher,
  wsServer:           Option[ChessWebSocketServer],
  shutdown:           () => Unit = () => (),
  terminalSerializer: TerminalEventJsonSerializer = NoOpTerminalEventJsonSerializer
)

/** Assembles the event distribution layer from [[AppConfig]].
 *
 *  This object is the single place in the composition root that decides how
 *  [[chess.application.event.AppEvent]]s are delivered to consumers.  Adding
 *  a new distribution strategy (e.g. Kafka) means adding a new branch in
 *  [[assemble]] and a corresponding [[EventMode]] variant — nothing else
 *  changes in the app composition or application layers.
 *
 *  === Current strategies ===
 *  - [[EventMode.InProcess]]: fan-out within the same JVM.  WebSocket is
 *    attached as an optional consumer when [[chess.config.WebSocketConfig.enabled]]
 *    is true; when disabled, the publisher becomes a silent no-op.
 *
 *  === History forwarding — SQLite mode ===
 *  Terminal events (GameFinished, GameResigned, SessionCancelled) are now written
 *  to `history_event_outbox` inside the same JDBC transaction as the game-state /
 *  session write via [[chess.application.port.repository.SessionGameStore.saveTerminal]]
 *  and [[chess.application.port.repository.SessionRepository.saveCancelWithOutbox]].
 *  [[chess.adapter.event.DurableHistoryEventPublisher]] is therefore NOT added to
 *  the fan-out for the SQLite path — the transactional write replaced it.
 *  [[HistoryOutboxForwarder]] continues to drain the outbox and forward payloads
 *  to History unchanged.
 *
 *  === History forwarding — InMemory mode ===
 *  No durable outbox exists.  [[HistoryHttpEventPublisher]] is used for best-effort
 *  HTTP delivery (same behaviour as before this change).  The terminal serialiser
 *  is a no-op so no outbox writes are attempted.
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
    val (historyPublishers, serializer, shutdownHistory) = historyBridge(config)

    if config.webSocket.enabled then
      // Registry is shared: ChessWebSocketServer registers/unregisters live
      // connections; WebSocketEventPublisher routes AppEvents to those connections.
      val registry  = WebSocketConnectionRegistry()
      val publisher = WebSocketEventPublisher(registry)
      val server    = ChessWebSocketServer(port = config.webSocket.port, registry)
      server.start()
      EventWiring(FanOutEventPublisher((Seq(publisher) ++ historyPublishers)*), Some(server), shutdownHistory, serializer)
    else
      EventWiring(FanOutEventPublisher(historyPublishers*), None, shutdownHistory, serializer)

  private def historyBridge(config: AppConfig): (Seq[EventPublisher], TerminalEventJsonSerializer, () => Unit) =
    if !config.history.enabled then
      (Seq.empty, NoOpTerminalEventJsonSerializer, () => ())
    else
      val url = config.history.baseUrl.get
      config.persistence match
        case PersistenceMode.SQLite =>
          // Terminal events are committed transactionally in saveTerminal /
          // saveCancelWithOutbox. DurableHistoryEventPublisher is not in the
          // fan-out here — the forwarder drains the outbox written by the store.
          val outbox    = SqliteHistoryEventOutbox(config.sqlite.get.path)
          val forwarder = HistoryOutboxForwarder(
            outbox         = outbox,
            historyBaseUrl = url,
            timeoutMillis  = config.history.timeoutMillis
          )
          forwarder.start()
          (Seq.empty, AppEventSerializer, () => { forwarder.stop(); outbox.close() })

        case PersistenceMode.InMemory =>
          System.err.println(
            "[chess] History forwarding is best-effort because PERSISTENCE_MODE is not sqlite"
          )
          (Seq(HistoryHttpEventPublisher(url, config.history.timeoutMillis)), NoOpTerminalEventJsonSerializer, () => ())
