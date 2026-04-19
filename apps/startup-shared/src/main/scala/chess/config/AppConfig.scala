package chess.config

/** Persistence backend for game and session state.
 *
 *  Only [[InMemory]] is supported at present.  Additional modes (e.g.
 *  PostgreSQL) will extend this enum when a concrete adapter exists.
 */
enum PersistenceMode:
  case InMemory

/** CORS configuration for development-time cross-origin browser access.
 *
 *  Applied to the entire composed HTTP surface in `bootstrap-server`.
 *  Business routes do not contain any CORS logic.
 *
 *  - [[enabled]]: when `false` (the default) no CORS headers are added;
 *    current behavior is fully preserved.
 *  - [[allowedOrigin]]: the `Origin` value a browser is allowed to send.
 *    Use `"*"` to allow any origin (permissive dev mode) or a specific
 *    origin like `"http://localhost:3000"` for tighter dev control.
 */
final case class CorsConfig(enabled: Boolean, allowedOrigin: String)

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
 *  branch in [[chess.startup.assembly.EventAssembly]] without touching application services.
 */
enum EventMode:
  case InProcess

/** AI provider runtime mode.
 *
 *  - [[Disabled]]: no AI provider is wired; AI HTTP requests return the existing
 *    `AI_NOT_CONFIGURED` response.
 *  - [[LocalDeterministic]]: use the in-process deterministic first-legal-move
 *    adapter. This remains the default so current flows are stable.
 *  - [[Remote]]: configuration shape for a future remote AI provider. The
 *    client adapter is introduced in a later slice.
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
  eventMode:   EventMode,
  cors:        CorsConfig,
  ai:          AiConfig
)
