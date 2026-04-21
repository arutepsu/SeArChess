package chess.historyservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.adapter.event.GameHistoryIngestionContract
import chess.history.{ArchiveMaterializer, HistoryIngestionService, RemoteGameArchiveClient}
import chess.history.sqlite.SqliteArchiveRepository
import fs2.Stream
import org.http4s.{HttpApp, Method, Request, Status, Uri}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.Files

class HistoryBoundaryContractSpec extends AnyFlatSpec with Matchers with EitherValues:

  "HistoryServiceConfig" should "disable the legacy ingestion alias by default" in {
    val config = HistoryServiceConfig.load(_ => None).value

    config.acceptLegacyIngestionPath shouldBe false
  }

  it should "allow the legacy ingestion alias to be explicitly enabled" in {
    val config = HistoryServiceConfig
      .load(key => Map("HISTORY_ACCEPT_LEGACY_INGESTION_PATH" -> "true").get(key))
      .value

    config.acceptLegacyIngestionPath shouldBe true
  }

  it should "reject unclear legacy ingestion alias values" in {
    HistoryServiceConfig
      .load(key => Map("HISTORY_ACCEPT_LEGACY_INGESTION_PATH" -> "yes").get(key))
      .left
      .value should include("HISTORY_ACCEPT_LEGACY_INGESTION_PATH must be true or false")
  }

  "HistoryRoutes" should "keep the legacy ingestion alias disabled by default" in {
    withRoutes() { http =>
      val response =
        http.run(post(GameHistoryIngestionContract.LegacyGameEventsPath, "{}")).unsafeRunSync()

      response.status shouldBe Status.NotFound
    }
  }

  it should "enable the legacy ingestion alias only when explicitly configured" in {
    withRoutes(acceptLegacy = true) { http =>
      val response =
        http.run(post(GameHistoryIngestionContract.LegacyGameEventsPath, "{}")).unsafeRunSync()

      response.status shouldBe Status.BadRequest
    }
  }

  it should "report internal boundary details from health without checking optional dependencies" in {
    withRoutes() { http =>
      val response =
        http.run(Request[IO](Method.GET, Uri.unsafeFromString("/health"))).unsafeRunSync()
      val body = response.bodyText.compile.string.unsafeRunSync()
      val json = ujson.read(body)

      response.status shouldBe Status.Ok
      json("downstreamIngestionPath").str shouldBe GameHistoryIngestionContract.GameEventsPath
      json("legacyIngestionPathEnabled").bool shouldBe false
      json("archiveReadAudience").str shouldBe "internal-for-now"
      json("gameServiceDependency").str shouldBe "optional-for-health"
    }
  }

  private def withRoutes(acceptLegacy: Boolean = false)(test: HttpApp[IO] => Unit): Unit =
    val historyDb = Files.createTempFile("searchess-history-boundary-", ".sqlite")
    val historyRepo = SqliteArchiveRepository(historyDb.toString)
    val ingestion = HistoryIngestionService(
      archiveClient = RemoteGameArchiveClient("http://127.0.0.1:1", timeoutMillis = 50),
      materializer = ArchiveMaterializer(),
      repository = historyRepo
    )

    try
      val http = HistoryRoutes(
        ingestion,
        historyRepo,
        acceptLegacyIngestionPath = acceptLegacy
      ).routes.orNotFound
      test(http)
    finally
      historyRepo.close()
      Files.deleteIfExists(historyDb)

  private def post(path: String, body: String): Request[IO] =
    Request[IO](
      method = Method.POST,
      uri = Uri.unsafeFromString(path),
      body = Stream.emits(body.getBytes(StandardCharsets.UTF_8)).covary[IO]
    )
