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
import chess.application.session.service.{
  PersistentSessionService,
  SessionSnapshotTransferService,
  SessionGameCommandService,
  SessionLifecycleService
}
import chess.adapter.http4s.route.{
  AuthenticatedSearchessUser,
  AuthenticatedUserClient,
  AuthenticatedUserClientError,
  HistoryArchiveClient,
  HistoryArchiveClientError
}
import org.http4s.*
import org.http4s.implicits.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString
import java.util.UUID

class Http4sArchiveRoutesSpec extends AnyFlatSpec with Matchers:

  private def app(
      userClient: Option[AuthenticatedUserClient] = None,
      historyClient: Option[HistoryArchiveClient] = None
  ): HttpApp[IO] =
    val events = new EventPublisher:
      override def publish(event: AppEvent): Unit = ()
    val sessionRepo = InMemorySessionRepository()
    val gameRepo = InMemoryGameRepository()
    val store = InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionLifecycleService = SessionLifecycleService(sessionRepo, events)
    val commands = SessionGameCommandService(sessionLifecycleService, store, events)
    val persistentSessionService =
      PersistentSessionService(sessionRepo, gameRepo, store, sessionLifecycleService)
    Http4sApp(
      DefaultGameService(commands, sessionLifecycleService, gameRepo, events),
      persistentSessionService,
      SessionSnapshotTransferService(persistentSessionService, store),
      gameRepo,
      store,
      userClient = userClient,
      historyArchiveClient = historyClient
    ).httpApp

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

  "GET /archive/mine" should "derive owner from the authenticated user and ignore client ownerUserId params" in {
    val authenticatedOwner = UUID.fromString("00000000-0000-0000-0000-000000000101")
    val clientSuppliedOwner = UUID.fromString("00000000-0000-0000-0000-000000000202")
    val history = RecordingHistoryArchiveClient()
    val users = StaticAuthenticatedUserClient(authenticatedOwner)
    val http = app(Some(users), Some(history))

    val resp = run(
      http,
      Request[IO](Method.GET, Uri.unsafeFromString(s"/archive/mine?ownerUserId=$clientSuppliedOwner"))
        .putHeaders(Header.Raw(CIString("Authorization"), "Bearer token"))
    )

    resp.status shouldBe Status.Ok
    history.requestedOwner shouldBe Some(authenticatedOwner)
    bodyJson(resp)("archives").arr.size shouldBe 0
  }

private final class StaticAuthenticatedUserClient(owner: UUID) extends AuthenticatedUserClient:
  override def getCurrentUser(
      authHeader: String
  ): Either[AuthenticatedUserClientError, AuthenticatedSearchessUser] =
    Right(
      AuthenticatedSearchessUser(
        userId = owner,
        nickname = Some("phase27"),
        onboardingRequired = false
      )
    )

private final class RecordingHistoryArchiveClient extends HistoryArchiveClient:
  var requestedOwner: Option[UUID] = None

  override def findByOwner(ownerUserId: UUID): Either[HistoryArchiveClientError, ujson.Value] =
    requestedOwner = Some(ownerUserId)
    Right(ujson.Obj("archives" -> ujson.Arr()))

