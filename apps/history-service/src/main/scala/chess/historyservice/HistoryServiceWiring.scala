package chess.historyservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.history.{ArchiveMaterializer, HistoryIngestionService, RemoteGameArchiveClient}
import chess.history.sqlite.SqliteArchiveRepository
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder

/** History Service composition root and HTTP runtime startup. */
object HistoryServiceWiring:

  def start(config: HistoryServiceConfig): HistoryServiceRuntime =
    val repository = SqliteArchiveRepository(config.dbPath)
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

    HistoryServiceRuntime(shutdown, repository)
