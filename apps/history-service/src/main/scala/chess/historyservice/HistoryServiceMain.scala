package chess.historyservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.history.{ArchiveMaterializer, HistoryIngestionService, RemoteGameArchiveClient}
import chess.history.sqlite.SqliteArchiveRepository
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder

object HistoryServiceMain:

  def main(args: Array[String]): Unit =
    val config = HistoryServiceConfig.loadOrExit()
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
    }))

    Thread.currentThread().join()
