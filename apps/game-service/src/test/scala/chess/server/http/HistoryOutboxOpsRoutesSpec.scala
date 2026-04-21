package chess.server.http

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.adapter.event.{AppEventSerializer, SqliteHistoryEventOutbox}
import chess.application.event.AppEvent
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Color, GameStatus}
import org.http4s.{Method, Request, Status, Uri}
import org.scalatest.OptionValues.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

class HistoryOutboxOpsRoutesSpec extends AnyFlatSpec with Matchers:

  private def tempDb(): String =
    Files.createTempFile("searchess-game-outbox-ops-", ".sqlite").toString

  private def run(routes: org.http4s.HttpApp[IO], request: Request[IO]) =
    routes.run(request).unsafeRunSync()

  private def bodyJson(response: org.http4s.Response[IO]): ujson.Value =
    ujson.read(response.bodyText.compile.string.unsafeRunSync())

  private def append(outbox: SqliteHistoryEventOutbox, event: AppEvent): Long =
    outbox.append(AppEventSerializer.serialize(event).value).toOption.get

  "HistoryOutboxOpsRoutes" should "return an empty summary for an empty outbox" in {
    val outbox = SqliteHistoryEventOutbox(tempDb())
    try
      val routes = HistoryOutboxOpsRoutes(Some(outbox)).routes.orNotFound
      val resp = run(routes, Request[IO](Method.GET, Uri.unsafeFromString("/ops/history-outbox")))
      val json = bodyJson(resp)

      resp.status shouldBe Status.Ok
      json("totalCount").num.toInt shouldBe 0
      json("pendingCount").num.toInt shouldBe 0
      json("deliveredCount").num.toInt shouldBe 0
      json("retryingCount").num.toInt shouldBe 0
      json("oldestPendingAt") shouldBe ujson.Null
      json("pendingByType").obj shouldBe empty
    finally outbox.close()
  }

  it should "show one pending row in summary and list views" in {
    val outbox = SqliteHistoryEventOutbox(tempDb())
    try
      val event = AppEvent.GameResigned(SessionId.random(), GameId.random(), Color.Black)
      val id = append(outbox, event)
      val routes = HistoryOutboxOpsRoutes(Some(outbox)).routes.orNotFound

      val summary = bodyJson(run(routes, Request[IO](Method.GET, Uri.unsafeFromString("/ops/history-outbox"))))
      summary("pendingCount").num.toInt shouldBe 1
      summary("deliveredCount").num.toInt shouldBe 0
      summary("retryingCount").num.toInt shouldBe 0
      summary("pendingByType")("game.resigned.v1").num.toInt shouldBe 1
      summary("oldestPendingAt").str should not be empty

      val list = bodyJson(run(routes, Request[IO](Method.GET, Uri.unsafeFromString("/ops/history-outbox/pending"))))
      list("items").arr should have size 1
      list("items")(0)("id").num.toLong shouldBe id
      list("items")(0)("eventType").str shouldBe "game.resigned.v1"
      list("items")(0)("status").str shouldBe "pending"
      list("items")(0)("pending").bool shouldBe true
    finally outbox.close()
  }

  it should "show retrying rows after failed attempts" in {
    val outbox = SqliteHistoryEventOutbox(tempDb())
    try
      val event = AppEvent.SessionCancelled(SessionId.random(), GameId.random())
      val id = append(outbox, event)
      outbox.markAttempted(id).toOption.get
      outbox.markFailed(id, "history down").toOption.get
      val routes = HistoryOutboxOpsRoutes(Some(outbox)).routes.orNotFound

      val summary = bodyJson(run(routes, Request[IO](Method.GET, Uri.unsafeFromString("/ops/history-outbox"))))
      summary("pendingCount").num.toInt shouldBe 1
      summary("retryingCount").num.toInt shouldBe 1

      val detail = bodyJson(run(routes, Request[IO](Method.GET, Uri.unsafeFromString(s"/ops/history-outbox/$id"))))
      detail("status").str shouldBe "retrying"
      detail("attempts").num.toInt shouldBe 1
      detail("lastAttemptedAt").str should not be empty
      detail("lastError").str shouldBe "history down"
      detail("payloadJson")("type").str shouldBe "game.session.cancelled.v1"
    finally outbox.close()
  }

  it should "show delivered counts while excluding delivered rows from pending list" in {
    val outbox = SqliteHistoryEventOutbox(tempDb())
    try
      val event = AppEvent.GameFinished(SessionId.random(), GameId.random(), GameStatus.Checkmate(Color.White))
      val id = append(outbox, event)
      outbox.markDelivered(id).toOption.get
      val routes = HistoryOutboxOpsRoutes(Some(outbox)).routes.orNotFound

      val summary = bodyJson(run(routes, Request[IO](Method.GET, Uri.unsafeFromString("/ops/history-outbox"))))
      summary("pendingCount").num.toInt shouldBe 0
      summary("deliveredCount").num.toInt shouldBe 1

      val list = bodyJson(run(routes, Request[IO](Method.GET, Uri.unsafeFromString("/ops/history-outbox/pending"))))
      list("items").arr shouldBe empty

      val detail = bodyJson(run(routes, Request[IO](Method.GET, Uri.unsafeFromString(s"/ops/history-outbox/$id"))))
      detail("status").str shouldBe "delivered"
      detail("pending").bool shouldBe false
      detail("deliveredAt").str should not be empty
    finally outbox.close()
  }

  it should "return 404 when no durable outbox is configured" in {
    val routes = HistoryOutboxOpsRoutes(None).routes.orNotFound
    val resp = run(routes, Request[IO](Method.GET, Uri.unsafeFromString("/ops/history-outbox")))
    val json = bodyJson(resp)

    resp.status shouldBe Status.NotFound
    json("code").str shouldBe "OUTBOX_NOT_CONFIGURED"
  }
