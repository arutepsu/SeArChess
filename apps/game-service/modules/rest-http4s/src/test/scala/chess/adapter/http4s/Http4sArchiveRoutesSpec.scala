package chess.adapter.http4s

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.adapter.repository.{
  InMemoryGameRepository,
  InMemorySessionGameStore,
  InMemorySessionRepository
}
import chess.application.DefaultGameService
import chess.application.event.AppEvent
import chess.application.port.event.EventPublisher
import chess.application.session.service.{SessionGameService, SessionService}
import org.http4s.*
import org.http4s.implicits.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class Http4sArchiveRoutesSpec extends AnyFlatSpec with Matchers:

  private def app(): HttpApp[IO] =
    val events = new EventPublisher:
      override def publish(event: AppEvent): Unit = ()
    val sessionRepo = InMemorySessionRepository()
    val gameRepo = InMemoryGameRepository()
    val store = InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionService = SessionService(sessionRepo, events)
    val commands = SessionGameService(sessionService, store, events)
    Http4sApp(DefaultGameService(commands, sessionService, gameRepo, events)).httpApp

  private def run(app: HttpApp[IO], req: Request[IO]): Response[IO] =
    app.run(req).unsafeRunSync()

  private def bodyJson(resp: Response[IO]): ujson.Value =
    ujson.read(resp.bodyText.compile.string.unsafeRunSync())

  private def jsonBody(s: String): fs2.Stream[IO, Byte] =
    fs2.Stream.emits(s.getBytes("UTF-8")).covary[IO]

  "GET /archive/games/{gameId}" should "return an archive snapshot for a cancelled session" in {
    val http = app()
    val create = run(http, Request[IO](Method.POST, uri"/sessions").withBodyStream(jsonBody("{}")))
    val created = bodyJson(create)
    val sessionId = created("session")("sessionId").str
    val gameId = created("session")("gameId").str

    run(
      http,
      Request[IO](Method.POST, Uri.unsafeFromString(s"/sessions/$sessionId/cancel"))
    ).status shouldBe Status.Ok

    val resp = run(http, Request[IO](Method.GET, Uri.unsafeFromString(s"/archive/games/$gameId")))
    resp.status shouldBe Status.Ok
    val json = bodyJson(resp)
    json("gameId").str shouldBe gameId
    json("sessionId").str shouldBe sessionId
    json("closure")("kind").str shouldBe "Cancelled"
    json("finalState")("game")("board").arr should have size 32
    json("finalState")("castlingRights")("whiteKingSide").bool shouldBe true
  }

  it should "return 409 while a game is still active" in {
    val http = app()
    val create = run(http, Request[IO](Method.POST, uri"/sessions").withBodyStream(jsonBody("{}")))
    val gameId = bodyJson(create)("session")("gameId").str

    val resp = run(http, Request[IO](Method.GET, Uri.unsafeFromString(s"/archive/games/$gameId")))
    resp.status shouldBe Status.Conflict
    bodyJson(resp)("code").str shouldBe "ARCHIVE_NOT_READY"
  }
