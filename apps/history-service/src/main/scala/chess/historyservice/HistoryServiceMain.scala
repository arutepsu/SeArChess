package chess.historyservice

<<<<<<< HEAD
import chess.observability.StructuredLog
=======
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.history.{ArchiveMaterializer, HistoryIngestionService, RemoteGameArchiveClient}
import chess.history.sqlite.SqliteArchiveRepository
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services)

object HistoryServiceMain:

  def main(args: Array[String]): Unit =
    val config = HistoryServiceConfig.loadOrExit()
<<<<<<< HEAD
    StructuredLog.info(
      "history-service",
      "startup_config",
      "httpHost" -> config.host,
      "httpPort" -> config.port,
      "gameServiceArchiveBaseUrl" -> config.gameServiceBaseUrl,
      "storage" -> "postgres-slick",
      "postgresSchema" -> config.postgresSchema,
      "deliveryMode" -> config.deliveryMode.toString,
      "redisStream" -> config.redisStream,
      "redisGroup" -> config.redisGroup,
      "acceptLegacyIngestionPath" -> config.acceptLegacyIngestionPath
    )

    val runtime = HistoryServiceWiring.start(config)
    StructuredLog.info(
      "history-service",
      "started",
      "httpHost" -> config.host,
      "httpPort" -> config.port,
      "healthPath" -> "/health",
      "downstreamIngestionPath" -> chess.adapter.event.GameHistoryIngestionContract.GameEventsPath,
      "legacyIngestionPathEnabled" -> config.acceptLegacyIngestionPath
    )
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      StructuredLog.info("history-service", "shutdown_started")
      runtime.shutdown()
      StructuredLog.info("history-service", "shutdown_completed")
=======
    println(s"[history] Game Service archive base URL: ${config.gameServiceBaseUrl}")
    println(s"[history] Archive DB: ${config.dbPath}")

    val repository = SqliteArchiveRepository(config.dbPath)
    val ingestion = HistoryIngestionService(
      archiveClient = RemoteGameArchiveClient(config.gameServiceBaseUrl, config.timeoutMillis),
      materializer = ArchiveMaterializer(),
      repository = repository
    )

    val httpApp = HistoryRoutes(ingestion, repository).routes.orNotFound
    val host = Host.fromString(config.host).getOrElse(throw RuntimeException(s"Invalid HISTORY_HTTP_HOST: ${config.host}"))
    val port = Port.fromInt(config.port).getOrElse(throw RuntimeException(s"Invalid HISTORY_HTTP_PORT: ${config.port}"))

    val (_, shutdown) = EmberServerBuilder
      .default[IO]
      .withHost(host)
      .withPort(port)
      .withHttpApp(httpApp)
      .build
      .allocated
      .unsafeRunSync()

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      shutdown.unsafeRunSync()
      repository.close()
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services)
    }))

    Thread.currentThread().join()
