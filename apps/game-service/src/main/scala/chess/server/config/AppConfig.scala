package chess.server.config

enum PersistenceMode:
<<<<<<< HEAD
  case Postgres
  case Mongo
<<<<<<< HEAD
=======
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
=======
>>>>>>> 2b1aa125 (real migration ok)
  case InMemory
  case SQLite

final case class SqliteConfig(path: String)

<<<<<<< HEAD
final case class PostgresConfig(
    url: String,
    user: String,
    password: String,
    schema: Option[String] = None
)

final case class MongoConfig(
    uri: String,
    database: String
):
  def databaseName: String = database

final case class CorsConfig(enabled: Boolean, allowedOrigin: String)

enum HistoryDeliveryMode:
  case Http, RedisStream

final case class HistoryForwardingConfig(
    enabled: Boolean,
    baseUrl: Option[String],
    timeoutMillis: Int,
    deliveryMode: HistoryDeliveryMode = HistoryDeliveryMode.Http,
<<<<<<< HEAD
    redisUrl: Option[String] = None,
    redisHost: Option[String] = None,
    redisPort: Int = 6379,
    redisStream: String = "searchess.history.archives",
=======
    redisHost: Option[String] = None,
    redisPort: Int = 6379,
>>>>>>> 8b003a1f (Use schema-isolated Slick Postgres persistence for history service)
    interaction: ServiceInteraction = ServiceInteraction.DownstreamAsynchronousHttp,
    startupPolicy: DependencyStartupPolicy = DependencyStartupPolicy.NotRequired,
    failureBehaviour: DependencyFailureBehaviour = DependencyFailureBehaviour.LogAndContinue
=======
final case class CorsConfig(enabled: Boolean, allowedOrigin: String)

final case class HistoryForwardingConfig(
  enabled:          Boolean,
  baseUrl:          Option[String],
  timeoutMillis:    Int,
  interaction:      ServiceInteraction = ServiceInteraction.DownstreamAsynchronousHttp,
  startupPolicy:    DependencyStartupPolicy = DependencyStartupPolicy.NotRequired,
  failureBehaviour: DependencyFailureBehaviour = DependencyFailureBehaviour.LogAndContinue
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
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
<<<<<<< HEAD
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
=======
  baseUrl:  String,
  testMode: Option[String] = None
)

final case class AiConfig(
  mode:             AiProviderMode,
  remote:           Option[RemoteAiConfig],
  timeoutMillis:    Int,
  defaultEngineId:  Option[String],
  interaction:      ServiceInteraction = ServiceInteraction.InternalSynchronousHttp,
  startupPolicy:    DependencyStartupPolicy = DependencyStartupPolicy.NotRequired,
  failureBehaviour: DependencyFailureBehaviour = DependencyFailureBehaviour.FailRequest
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
)

/** Fully resolved Game Service runtime configuration. */
final case class AppConfig(
<<<<<<< HEAD
    http: HttpConfig,
    webSocket: WebSocketConfig,
    persistence: PersistenceMode,
    sqlite: Option[SqliteConfig],
    postgres: Option[PostgresConfig],
    mongo: Option[MongoConfig],
    eventMode: EventMode,
    cors: CorsConfig,
    history: HistoryForwardingConfig,
    ai: AiConfig,
    migrationAdminEnabled: Boolean = false,
    migrationAdminToken: Option[String] = None
<<<<<<< HEAD
=======
  http:        HttpConfig,
  webSocket:   WebSocketConfig,
  persistence: PersistenceMode,
  sqlite:      Option[SqliteConfig],
  eventMode:   EventMode,
  cors:        CorsConfig,
  history:     HistoryForwardingConfig,
  ai:          AiConfig
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
=======
>>>>>>> 2b1aa125 (real migration ok)
)
