package chess.historyservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.history.{ArchiveMaterializer, ArchiveRepository, HistoryIngestionService, RemoteGameArchiveClient}
import chess.history.postgres.HistoryFlywaySchemaInitializer
import chess.history.redis.RedisStreamHistoryConsumer
import chess.history.slick.SlickPostgresArchiveRepository
import chess.observability.StructuredLog
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder
import slick.jdbc.PostgresProfile.api.Database

/** History Service composition root and HTTP runtime startup. */
object HistoryServiceWiring:

  def start(config: HistoryServiceConfig): HistoryServiceRuntime =
    val (repository, closeStorage) = buildRepository(config)
    val ingestion = HistoryIngestionService(
      archiveClient = RemoteGameArchiveClient(config.gameServiceBaseUrl, config.timeoutMillis),
      materializer  = ArchiveMaterializer(),
      repository    = repository
    )

    val httpApp = HistoryRoutes(
      ingestion,
      repository,
      acceptLegacyIngestionPath = config.acceptLegacyIngestionPath
    ).routes.orNotFound
    val host = Host
      .fromString(config.host)
      .getOrElse(throw RuntimeException(s"Invalid HISTORY_HTTP_HOST: ${config.host}"))
    val port = Port
      .fromInt(config.port)
      .getOrElse(throw RuntimeException(s"Invalid HISTORY_HTTP_PORT: ${config.port}"))

    val (_, shutdown) = EmberServerBuilder
      .default[IO]
      .withHost(host)
      .withPort(port)
      .withHttpApp(httpApp)
      .build
      .allocated
      .unsafeRunSync()

    val stopConsumer = startConsumer(config, ingestion)
    HistoryServiceRuntime(shutdown, closeStorage, stopConsumer)

  private def startConsumer(
      config: HistoryServiceConfig,
      ingestion: HistoryIngestionService
  ): () => Unit =
    config.deliveryMode match
      case HistoryDeliveryMode.Http => () => ()
      case HistoryDeliveryMode.RedisStream =>
        val host = config.redisHost.getOrElse(
          throw RuntimeException("History Redis ingestion enabled but HISTORY_REDIS_URL/REDIS_HOST is not configured")
        )
        val consumer = RedisStreamHistoryConsumer(
          host         = host,
          port         = config.redisPort,
          ingest       = ingestion.ingestEventJson,
          streamName   = config.redisStream,
          groupName    = config.redisGroup,
          consumerName = config.redisConsumerName
        )
        consumer.start()
        () => consumer.stop()

  private def buildRepository(config: HistoryServiceConfig): (ArchiveRepository, () => Unit) =
    val result = HistoryFlywaySchemaInitializer.migrate(
      config.postgresUrl,
      config.postgresUser,
      config.postgresPassword,
      config.postgresSchema
    )
    StructuredLog.info(
      "history-service",
      "postgres_schema_migrated",
      "migrationsExecuted" -> result.migrationsExecuted,
      "schemaVersion"      -> result.schemaVersion.getOrElse("none")
    )
    val db   = Database.forURL(
      url      = config.postgresUrl,
      user     = config.postgresUser,
      password = config.postgresPassword,
      driver   = "org.postgresql.Driver"
    )
    val repo = SlickPostgresArchiveRepository(db, config.postgresSchema)
    (repo, () => db.close())
