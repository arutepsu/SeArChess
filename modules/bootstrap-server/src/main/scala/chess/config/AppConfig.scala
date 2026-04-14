package chess.config

/** Runtime deployment mode.
 *
 *  Determines which adapters are started alongside the backend.
 *  - [[Desktop]]: HTTP + WebSocket + GUI + TUI
 *  - [[Server]]:  HTTP + WebSocket only (no GUI or TUI)
 */
enum AppMode:
  case Desktop, Server

/** Persistence backend for game and session state.
 *
 *  Only [[InMemory]] is supported at present.  Additional modes (e.g.
 *  PostgreSQL) will extend this enum when a concrete adapter exists.
 */
enum PersistenceMode:
  case InMemory

/** Event distribution strategy.
 *
 *  Controls how [[chess.application.event.AppEvent]]s emitted by application
 *  services are delivered to consumers.
 *
 *  - [[InProcess]]: fan-out delivery within the same JVM.  WebSocket is one
 *    optional consumer when `webSocket.enabled` is true.  This is the only
 *    supported mode at present.
 *
 *  Future modes (e.g. `Kafka`) will extend this enum and add a corresponding
 *  branch in [[chess.EventAssembly]] without touching application services.
 */
enum EventMode:
  case InProcess

/** Normalised HTTP server configuration. */
final case class HttpConfig(host: String, port: Int)

/** Normalised WebSocket server configuration. */
final case class WebSocketConfig(enabled: Boolean, port: Int)

/** Fully resolved runtime configuration for the chess server.
 *
 *  Produced by [[ConfigLoader.load]] after all environment variables have
 *  been read and validated.  All fields carry their final, typed values;
 *  no raw strings remain.
 */
final case class AppConfig(
  mode:        AppMode,
  http:        HttpConfig,
  webSocket:   WebSocketConfig,
  persistence: PersistenceMode,
  eventMode:   EventMode
)
