package chess.server.assembly

import chess.adapter.event.{AppEventSerializer, FanOutEventPublisher, HistoryEventOutbox, HistoryHttpEventPublisher, HistoryOutboxForwarder, SqliteHistoryEventOutbox}
import chess.adapter.websocket.{ChessWebSocketServer, WebSocketConnectionRegistry, WebSocketEventPublisher}
import chess.application.port.event.{EventPublisher, NoOpTerminalEventJsonSerializer, TerminalEventJsonSerializer}
import chess.server.config.{AppConfig, EventMode, PersistenceMode}

/** Game Service event runtime produced by [[EventAssembly.assemble]].
 *
 *  This is deliberately owned by `apps/game-service`, not `startup-shared`,
 *  because it starts service runtime infrastructure:
 *
 *  - WebSocket server lifecycle
 *  - History HTTP forwarding / SQLite outbox draining
 *  - terminal event JSON serialization for the Game -> History outbox
 *
 *  [[coreEvents]] exposes only the event dependencies needed by the Game
 *  Service application assembly.
 */
final case class EventWiring(
  publisher:          EventPublisher,
  wsServer:           Option[ChessWebSocketServer],
  shutdown:           () => Unit = () => (),
  terminalSerializer: TerminalEventJsonSerializer = NoOpTerminalEventJsonSerializer,
  historyOutbox:      Option[HistoryEventOutbox] = None
):
  def coreEvents: CoreEventBindings =
    CoreEventBindings(publisher, terminalSerializer)

/** Assembles Game Service event distribution from [[AppConfig]].
 *
 *  This object is the Game Service composition root for event runtime concerns.
 *  Shared local UI apps do not depend on it; they use their own local startup
 *  assembly with a silent publisher.
 *
 *  Current strategies:
 *  - [[EventMode.InProcess]]: fan-out delivery within this JVM. WebSocket is
 *    attached as an optional consumer when enabled.
 *
 *  History forwarding in SQLite mode:
 *  terminal events are written to `history_event_outbox` inside the same JDBC
 *  transaction as the game-state / session write via
 *  [[chess.application.port.repository.SessionGameStore.saveTerminal]] and
 *  [[chess.application.port.repository.SessionRepository.saveCancelWithOutbox]].
 *  The background [[HistoryOutboxForwarder]] drains that durable table.
 *
 *  History forwarding in in-memory mode remains best-effort HTTP because there
 *  is no durable store.
 */
object EventAssembly:

  def assemble(config: AppConfig): EventWiring =
    config.eventMode match
      case EventMode.InProcess => assembleInProcess(config)

  private def assembleInProcess(config: AppConfig): EventWiring =
    val (historyPublishers, serializer, historyOutbox, shutdownHistory) = historyBridge(config)

    if config.webSocket.enabled then
      val registry  = WebSocketConnectionRegistry()
      val publisher = WebSocketEventPublisher(registry)
      val server    = ChessWebSocketServer(port = config.webSocket.port, registry)
      server.start()
      EventWiring(
        FanOutEventPublisher((Seq(publisher) ++ historyPublishers)*),
        Some(server),
        shutdownHistory,
        serializer,
        historyOutbox
      )
    else
      EventWiring(FanOutEventPublisher(historyPublishers*), None, shutdownHistory, serializer, historyOutbox)

  private def historyBridge(config: AppConfig): (Seq[EventPublisher], TerminalEventJsonSerializer, Option[HistoryEventOutbox], () => Unit) =
    if !config.history.enabled then
      (Seq.empty, NoOpTerminalEventJsonSerializer, None, () => ())
    else
      val url = config.history.baseUrl.get
      config.persistence match
        case PersistenceMode.SQLite =>
          val outbox    = SqliteHistoryEventOutbox(config.sqlite.get.path)
          val forwarder = HistoryOutboxForwarder(
            outbox         = outbox,
            historyBaseUrl = url,
            timeoutMillis  = config.history.timeoutMillis
          )
          forwarder.start()
          (Seq.empty, AppEventSerializer, Some(outbox), () => { forwarder.stop(); outbox.close() })

        case PersistenceMode.InMemory =>
          System.err.println(
            "[chess] History forwarding is best-effort because PERSISTENCE_MODE is not sqlite"
          )
          (Seq(HistoryHttpEventPublisher(url, config.history.timeoutMillis)), NoOpTerminalEventJsonSerializer, None, () => ())
