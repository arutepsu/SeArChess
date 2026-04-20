package chess.config

/** Persistence backend for game and session state. */
enum PersistenceMode:
  case InMemory
  case SQLite

/** SQLite persistence configuration. */
final case class SqliteConfig(path: String)

/** CORS configuration for development-time cross-origin browser access.
 *
 *  Applied to the entire composed HTTP surface in `game-service`.
 *  Business routes do not contain any CORS logic.
 *
 *  - [[enabled]]: when `false` (the default) no CORS headers are added;
 *    current behavior is fully preserved.
 *  - [[allowedOrigin]]: the `Origin` value a browser is allowed to send.
 *    Use `"*"` to allow any origin (permissive dev mode) or a specific
 *    origin like `"http://localhost:3000"` for tighter dev control.
 */
final case class CorsConfig(enabled: Boolean, allowedOrigin: String)

/** History Service event forwarding.
 *
 *  This is a local/dev bridge, not a broker. In SQLite Game Service mode,
 *  terminal events are written to a durable outbox and retried by the Game
 *  Service runtime. In in-memory mode, forwarding remains best-effort HTTP.
 */
final case class HistoryForwardingConfig(
  enabled:       Boolean,
  baseUrl:       Option[String],
  timeoutMillis: Int
)

/** Event distribution strategy.
 *
 *  Controls how [[chess.application.event.AppEvent]]s emitted by application
 *  services are delivered to consumers.
 *
 *  - [[InProcess]]: fan-out delivery within the same JVM.  WebSocket is one
 *    optional consumer when `webSocket.enabled` is true.  This is the only
 *    supported mode at present.
 *
 *  Future modes (e.g. `Kafka`) would extend this enum and add a corresponding
 *  branch in the owning service runtime assembly without touching application
 *  services.
 */
enum EventMode:
  case InProcess

/** AI provider runtime mode.
 *
 *  - [[Disabled]]: no AI provider is wired; AI HTTP requests return the existing
 *    `AI_NOT_CONFIGURED` response.
 *  - [[LocalDeterministic]]: use the in-process deterministic first-legal-move
 *    adapter. This remains as an explicit local/dev fallback only.
 *  - [[Remote]]: delegate to the Python `searchess-ai-service` via
 *    `POST /v1/move-suggestions`. This is the default Game Service runtime
 *    path.
 */
enum AiProviderMode:
  case Disabled
  case LocalDeterministic
  case Remote

/** Normalised HTTP server configuration. */
final case class HttpConfig(host: String, port: Int)

/** Normalised WebSocket server configuration. */
final case class WebSocketConfig(enabled: Boolean, port: Int)

/** Normalised remote AI provider configuration.
 *
 *  [[baseUrl]] is intentionally a string at this layer; the future remote
 *  adapter will parse it into whichever HTTP client URI type it owns.
 */
final case class RemoteAiConfig(baseUrl: String)

/** Normalised AI runtime configuration. */
final case class AiConfig(
  mode:             AiProviderMode,
  remote:           Option[RemoteAiConfig],
  timeoutMillis:    Int,
  defaultEngineId:  Option[String]
)

/** Fully resolved runtime configuration for the chess server.
 *
 *  Produced by [[ConfigLoader.load]] after all environment variables have
 *  been read and validated.  All fields carry their final, typed values;
 *  no raw strings remain.
 *
 *  App selection (server, GUI, TUI) is determined by the SBT project and
 *  entry point, not by this config.
 */
final case class AppConfig(
  http:        HttpConfig,
  webSocket:   WebSocketConfig,
  persistence: PersistenceMode,
  sqlite:      Option[SqliteConfig],
  eventMode:   EventMode,
  cors:        CorsConfig,
  history:     HistoryForwardingConfig,
  ai:          AiConfig
)
