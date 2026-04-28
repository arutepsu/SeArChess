package chess.server.config

enum PersistenceMode:
  case Postgres
  case InMemory
  case SQLite

final case class SqliteConfig(path: String)

final case class PostgresConfig(
    url: String,
    user: String,
    password: String
)

final case class CorsConfig(enabled: Boolean, allowedOrigin: String)

final case class HistoryForwardingConfig(
    enabled: Boolean,
    baseUrl: Option[String],
    timeoutMillis: Int,
    interaction: ServiceInteraction = ServiceInteraction.DownstreamAsynchronousHttp,
    startupPolicy: DependencyStartupPolicy = DependencyStartupPolicy.NotRequired,
    failureBehaviour: DependencyFailureBehaviour = DependencyFailureBehaviour.LogAndContinue
)

enum ServiceInteraction:
  case InternalSynchronousHttp
  case DownstreamAsynchronousHttp

enum DependencyStartupPolicy:
  case Required
  case NotRequired

enum DependencyFailureBehaviour:
  case FailRequest
  case LogAndContinue

enum EventMode:
  case InProcess

enum AiProviderMode:
  case Disabled
  case LocalDeterministic
  case Remote

final case class HttpConfig(host: String, port: Int)

final case class WebSocketConfig(enabled: Boolean, port: Int)

final case class RemoteAiConfig(
    baseUrl: String,
    testMode: Option[String] = None
)

final case class AiConfig(
    mode: AiProviderMode,
    remote: Option[RemoteAiConfig],
    timeoutMillis: Int,
    defaultEngineId: Option[String],
    interaction: ServiceInteraction = ServiceInteraction.InternalSynchronousHttp,
    startupPolicy: DependencyStartupPolicy = DependencyStartupPolicy.NotRequired,
    failureBehaviour: DependencyFailureBehaviour = DependencyFailureBehaviour.FailRequest
)

/** Fully resolved Game Service runtime configuration. */
final case class AppConfig(
    http: HttpConfig,
    webSocket: WebSocketConfig,
    persistence: PersistenceMode,
    sqlite: Option[SqliteConfig],
    postgres: Option[PostgresConfig],
    eventMode: EventMode,
    cors: CorsConfig,
    history: HistoryForwardingConfig,
    ai: AiConfig
)
