package chess.server.assembly

<<<<<<< HEAD
import chess.adapter.event.{
  AppEventSerializer,
  FanOutEventPublisher,
  GameStreamEvent,
  HistoryEventOutbox,
  HistoryHttpEventPublisher,
  HistoryOutboxForwarder,
  RedisStreamHistoryPublisher,
  SqliteHistoryEventOutbox
}
import redis.clients.jedis.JedisPooled
import chess.adapter.websocket.{
  ChessWebSocketServer,
  WebSocketConnectionRegistry,
  WebSocketEventPublisher
}
import chess.application.port.event.{
  EventPublisher,
  NoOpTerminalEventJsonSerializer,
  TerminalEventJsonSerializer
}
import chess.observability.StructuredLog
import chess.server.config.{AppConfig, EventMode, HistoryDeliveryMode, PersistenceMode}

/** Game Service event runtime produced by [[EventAssembly.assemble]].
  *
  * This is deliberately owned by `apps/game-service`, not `startup-shared`, because it starts
  * service runtime infrastructure:
  *
  *   - WebSocket server lifecycle
  *   - History HTTP forwarding / SQLite outbox draining
  *   - terminal event JSON serialization for the Game -> History outbox
  *
  * [[coreEvents]] exposes only the event dependencies needed by the Game Service application
  * assembly.
  */
=======
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
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
final case class EventWiring(
    publisher: EventPublisher,
    wsServer: Option[ChessWebSocketServer],
    shutdown: () => Unit = () => (),
    terminalSerializer: TerminalEventJsonSerializer = NoOpTerminalEventJsonSerializer,
    historyOutbox: Option[HistoryEventOutbox] = None
):
  def coreEvents: CoreEventBindings =
    CoreEventBindings(publisher, terminalSerializer)

/** Assembles Game Service event distribution from [[AppConfig]].
<<<<<<< HEAD
  *
  * This object is the Game Service composition root for event runtime concerns. Shared local UI
  * apps do not depend on it; they use their own local startup assembly with a silent publisher.
  *
  * Current strategies:
  *   - [[EventMode.InProcess]]: fan-out delivery within this JVM. WebSocket is attached as an
  *     optional consumer when enabled.
  *
  * History forwarding in SQLite mode: terminal events are written to `history_event_outbox` inside
  * the same JDBC transaction as the game-state / session write via
  * [[chess.application.port.repository.SessionGameStore.saveTerminal]] and
  * [[chess.application.port.repository.SessionRepository.saveCancelWithOutbox]]. The background
  * [[HistoryOutboxForwarder]] drains that durable table.
  *
  * History forwarding in in-memory mode remains best-effort HTTP because there is no durable store.
  */
=======
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
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
object EventAssembly:

  def assemble(config: AppConfig): EventWiring =
    config.eventMode match
      case EventMode.InProcess => assembleInProcess(config)

  private def assembleInProcess(config: AppConfig): EventWiring =
    val (historyPublishers, serializer, historyOutbox, shutdownHistory) = historyBridge(config)

    if config.webSocket.enabled then
      val registry = WebSocketConnectionRegistry()
      val publisher = WebSocketEventPublisher(registry)
      val server = ChessWebSocketServer(port = config.webSocket.port, registry)
      server.start()
      EventWiring(
        FanOutEventPublisher((Seq(publisher) ++ historyPublishers)*),
        Some(server),
        shutdownHistory,
        serializer,
        historyOutbox
      )
    else
      EventWiring(
        FanOutEventPublisher(historyPublishers*),
        None,
        shutdownHistory,
        serializer,
        historyOutbox
      )

  private def historyBridge(
      config: AppConfig
  ): (Seq[EventPublisher], TerminalEventJsonSerializer, Option[HistoryEventOutbox], () => Unit) =
    if !config.history.enabled then (Seq.empty, NoOpTerminalEventJsonSerializer, None, () => ())
    else
      config.history.deliveryMode match
        case HistoryDeliveryMode.RedisStream =>
          val host  = config.history.redisHost.getOrElse(
            throw IllegalArgumentException("History Redis delivery enabled but HISTORY_REDIS_URL/REDIS_HOST is not configured")
          )
          val port  = config.history.redisPort
          val jedis = JedisPooled(host, port)
          StructuredLog.info(
            "game-service",
            "history_stream_delivery_configured",
            "redisHost" -> host,
            "redisPort" -> port,
            "stream"    -> config.history.redisStream
          )
          (
            Seq(RedisStreamHistoryPublisher(jedis, config.history.redisStream)),
            NoOpTerminalEventJsonSerializer,
            None,
            () => jedis.close()
          )

        case HistoryDeliveryMode.Http =>
          val url = config.history.baseUrl.getOrElse(
            throw IllegalArgumentException("History delivery enabled but HISTORY_BASE_URL is not configured")
          )
          config.persistence match
            case PersistenceMode.SQLite =>
              val outbox = SqliteHistoryEventOutbox(
                config.sqlite
                  .getOrElse(
                    throw IllegalArgumentException(
                      "SQLite persistence required for history outbox but sqlite config is missing"
                    )
                  )
                  .path
              )
              val forwarder = HistoryOutboxForwarder(
                outbox         = outbox,
                historyBaseUrl = url,
                timeoutMillis  = config.history.timeoutMillis
              )
              forwarder.start()
              (Seq.empty, AppEventSerializer, Some(outbox), () => { forwarder.stop(); outbox.close() })

            case PersistenceMode.InMemory =>
              StructuredLog.warn(
                "game-service",
                "history_forwarding_best_effort",
                "reason"      -> "PERSISTENCE_MODE is not sqlite",
                "persistence" -> config.persistence.toString,
                "historyBaseUrl" -> url
              )
              (Seq(HistoryHttpEventPublisher(url, config.history.timeoutMillis)), NoOpTerminalEventJsonSerializer, None, () => ())

            case PersistenceMode.Postgres =>
              StructuredLog.warn(
                "game-service",
                "history_forwarding_best_effort",
                "reason"      -> "durable history outbox is currently sqlite-only",
                "persistence" -> config.persistence.toString,
                "historyBaseUrl" -> url
              )
              (Seq(HistoryHttpEventPublisher(url, config.history.timeoutMillis)), NoOpTerminalEventJsonSerializer, None, () => ())

            case PersistenceMode.Mongo =>
              StructuredLog.warn(
                "game-service",
                "history_forwarding_best_effort",
                "reason"      -> "durable history outbox is currently sqlite-only",
                "persistence" -> config.persistence.toString,
                "historyBaseUrl" -> url
              )
              (Seq(HistoryHttpEventPublisher(url, config.history.timeoutMillis)), NoOpTerminalEventJsonSerializer, None, () => ())
