package chess.historyservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.adapter.event.GameHistoryIngestionContract
import chess.application.session.model.SessionIds.GameId
import chess.history.{ArchiveMaterializer, ArchiveRecord, ArchiveRepository, ArchiveRepositoryError, HistoryIngestionService, RemoteGameArchiveClient}
import fs2.Stream
import org.http4s.{HttpApp, Method, Request, Status, Uri}
import org.scalatest.{EitherValues, OptionValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

import scala.collection.mutable

class HistoryBoundaryContractSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  private val minimalEnv: Map[String, String] = Map(
    "HISTORY_POSTGRES_URL" -> "jdbc:postgresql://localhost/test"
  )
  private def withEnv(extra: (String, String)*)(key: String): Option[String] =
    (minimalEnv ++ extra.toMap).get(key)

  "HistoryServiceConfig" should "disable the legacy ingestion alias by default" in {
    val config = HistoryServiceConfig.load(withEnv()).value
    config.acceptLegacyIngestionPath shouldBe false
  }

  it should "allow the legacy ingestion alias to be explicitly enabled" in {
    val config = HistoryServiceConfig
      .load(withEnv("HISTORY_ACCEPT_LEGACY_INGESTION_PATH" -> "true"))
      .value
    config.acceptLegacyIngestionPath shouldBe true
  }

  it should "reject unclear legacy ingestion alias values" in {
    HistoryServiceConfig
      .load(key => Map("HISTORY_ACCEPT_LEGACY_INGESTION_PATH" -> "yes").get(key))
      .left
      .value should include("HISTORY_ACCEPT_LEGACY_INGESTION_PATH must be true or false")
  }

  it should "fail when HISTORY_POSTGRES_URL is absent" in {
    HistoryServiceConfig
      .load(_ => None)
      .left
      .value should include("HISTORY_POSTGRES_URL is required")
  }

  it should "parse Redis Streams ingestion config" in {
    val config = HistoryServiceConfig
      .load(
        withEnv(
          "HISTORY_INGESTION_MODE" -> "redis-stream",
          "HISTORY_REDIS_URL" -> "redis://redis:6379",
          "HISTORY_REDIS_STREAM" -> "searchess.history.archives",
          "HISTORY_REDIS_GROUP" -> "history-service",
          "HISTORY_REDIS_CONSUMER_NAME" -> "history-service-test"
        )
      )
      .value

    config.deliveryMode shouldBe HistoryDeliveryMode.RedisStream
    config.redisUrl.value shouldBe "redis://redis:6379"
    config.redisHost.value shouldBe "redis"
    config.redisPort shouldBe 6379
    config.redisStream shouldBe "searchess.history.archives"
    config.redisGroup shouldBe "history-service"
    config.redisConsumerName shouldBe "history-service-test"
  }

  it should "reject Redis Streams ingestion without Redis config" in {
    HistoryServiceConfig
      .load(withEnv("HISTORY_INGESTION_MODE" -> "redis-stream"))
      .left
      .value should include("HISTORY_REDIS_URL or REDIS_HOST is required")
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
    val historyRepo = TestArchiveRepository()
    val ingestion = HistoryIngestionService(
      archiveClient = RemoteGameArchiveClient("http://127.0.0.1:1", timeoutMillis = 50),
      materializer  = ArchiveMaterializer(),
      repository    = historyRepo
    )
    val http = HistoryRoutes(
      ingestion,
      historyRepo,
      acceptLegacyIngestionPath = acceptLegacy
    ).routes.orNotFound
    test(http)

  private def post(path: String, body: String): Request[IO] =
    Request[IO](
      method = Method.POST,
      uri    = Uri.unsafeFromString(path),
      body   = Stream.emits(body.getBytes("UTF-8")).covary[IO]
    )

private class TestArchiveRepository extends ArchiveRepository:
  import chess.application.session.model.SessionIds.GameId
  import chess.history.{ArchiveRecord, ArchiveRepositoryError}

  private val store = mutable.Map.empty[GameId, ArchiveRecord]
  override def upsert(r: ArchiveRecord): Either[ArchiveRepositoryError, Unit] =
    store(r.gameId) = r; Right(())
  override def findByGameId(id: GameId): Either[ArchiveRepositoryError, Option[ArchiveRecord]] =
    Right(store.get(id))
  override def findByOwner(ownerUserId: java.util.UUID): Either[ArchiveRepositoryError, List[ArchiveRecord]] =
    Right(store.values.filter(_.ownerUserId.contains(ownerUserId)).toList)
