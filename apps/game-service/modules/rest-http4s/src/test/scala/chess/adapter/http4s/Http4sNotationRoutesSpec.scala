package chess.adapter.http4s

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.adapter.http4s.Http4sApp
import chess.adapter.http4s.route.{Http4sNotationRoutes, Http4sSessionRoutes}
import chess.adapter.repository.{
  InMemoryGameRepository,
  InMemorySessionGameStore,
  InMemorySessionRepository
}
import chess.application.DefaultGameService
import chess.application.session.service.{
  PersistentSessionService,
  SessionSnapshotTransferService,
  SessionGameCommandService,
  SessionLifecycleService
}
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.util.UUID

class Http4sNotationRoutesSpec extends AnyFlatSpec with Matchers:

  private case class Fixture(gameRoutes: HttpApp[IO], sessionRoutes: HttpApp[IO])

  private def makeFixture(): Fixture =
    val sessionRepo = InMemorySessionRepository()
    val gameRepo = InMemoryGameRepository()
    val store = InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionLifecycleService = SessionLifecycleService(sessionRepo, _ => ())
    val commands = SessionGameCommandService(sessionLifecycleService, store, _ => ())
    val gameService = DefaultGameService(commands, sessionLifecycleService, gameRepo, _ => ())
    val persistentSessionService =
      PersistentSessionService(sessionRepo, gameRepo, store, sessionLifecycleService)

    Fixture(
      Http4sNotationRoutes(gameRepo, store).routes.orNotFound,
      Http4sSessionRoutes(
        gameService,
        persistentSessionService,
        SessionSnapshotTransferService(persistentSessionService, store)
      ).routes.orNotFound
    )

  private def run(routes: HttpApp[IO], req: Request[IO]): Response[IO] =
    routes.run(req).unsafeRunSync()

  private def bodyJson(resp: Response[IO]): ujson.Value =
    ujson.read(resp.bodyText.compile.string.unsafeRunSync())

  private def jsonBody(s: String): fs2.Stream[IO, Byte] =
    fs2.Stream.emits(s.getBytes("UTF-8")).covary[IO]

  private def createGameId(fixture: Fixture): String =
    val response =
      run(fixture.sessionRoutes, Request[IO](Method.POST, uri"/sessions").withBodyStream(jsonBody("{}")))
    bodyJson(response)("session")("gameId").str

  "GET /games/{gameId}/notation/fen" should "export FEN notation for a game" in {
    val fixture = makeFixture()
    val gameId = createGameId(fixture)

    val resp =
      run(fixture.gameRoutes, Request[IO](Method.GET, Uri.unsafeFromString(s"/games/$gameId/notation/fen")))

    resp.status shouldBe Status.Ok
    val json = bodyJson(resp)
    json("format").str shouldBe "FEN"
    json("notation").str should include(" w ")
  }

  "GET /games/{gameId}/notation/pgn" should "export PGN notation for a game" in {
    val fixture = makeFixture()
    val gameId = createGameId(fixture)

    val resp =
      run(fixture.gameRoutes, Request[IO](Method.GET, Uri.unsafeFromString(s"/games/$gameId/notation/pgn")))

    resp.status shouldBe Status.Ok
    val json = bodyJson(resp)
    json("format").str shouldBe "PGN"
    json("notation").str should not be empty
  }

  it should "return 400 for a non-UUID game id" in {
    val fixture = makeFixture()
    val resp = run(fixture.gameRoutes, Request[IO](Method.GET, uri"/games/not-a-uuid/notation/fen"))

    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "BAD_REQUEST"
  }

  it should "return 404 for an unknown game id" in {
    val fixture = makeFixture()
    val unknown = UUID.randomUUID().toString
    val resp =
      run(fixture.gameRoutes, Request[IO](Method.GET, Uri.unsafeFromString(s"/games/$unknown/notation/fen")))

    resp.status shouldBe Status.NotFound
    bodyJson(resp)("code").str shouldBe "GAME_NOT_FOUND"
  }

  "POST /sessions/import-notation" should "create a new session from valid FEN" in {
    val fixture = makeFixture()
    val body =
      """{"format":"FEN","notation":"rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"}"""

    val resp = run(
      fixture.gameRoutes,
      Request[IO](Method.POST, uri"/sessions/import-notation").withBodyStream(jsonBody(body))
    )

    resp.status shouldBe Status.Created
    val json = bodyJson(resp)
    json("session")("sessionId").str should not be empty
    json("session")("gameId").str shouldBe json("game")("gameId").str
    json("game")("board").arr should have size 32
  }

  it should "return 400 for invalid FEN" in {
    val fixture = makeFixture()
    val body = """{"format":"FEN","notation":"not a fen"}"""

    val resp = run(
      fixture.gameRoutes,
      Request[IO](Method.POST, uri"/sessions/import-notation").withBodyStream(jsonBody(body))
    )

    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "INVALID_NOTATION"
  }

  it should "return 400 for an unsupported notation format" in {
    val fixture = makeFixture()
    val body = """{"format":"JSON","notation":"{}"}"""

    val resp = run(
      fixture.gameRoutes,
      Request[IO](Method.POST, uri"/sessions/import-notation").withBodyStream(jsonBody(body))
    )

    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "BAD_REQUEST"
  }

  it should "create a new session from PGN with moves" in {
    val fixture = makeFixture()
    val body = """{"format":"PGN","notation":"1. e4 e5 *"}"""

    val resp = run(
      fixture.gameRoutes,
      Request[IO](Method.POST, uri"/sessions/import-notation").withBodyStream(jsonBody(body))
    )

    resp.status shouldBe Status.Created
    val json = bodyJson(resp)
    json("session")("sessionId").str should not be empty
    json("session")("gameId").str shouldBe json("game")("gameId").str
    json("game")("moveHistory").arr should have size 2
    json("game")("currentPlayer").str shouldBe "White"
  }

  it should "create a new session from empty PGN" in {
    val fixture = makeFixture()
    val body = """{"format":"PGN","notation":"*"}"""

    val resp = run(
      fixture.gameRoutes,
      Request[IO](Method.POST, uri"/sessions/import-notation").withBodyStream(jsonBody(body))
    )

    resp.status shouldBe Status.Created
    val json = bodyJson(resp)
    json("game")("moveHistory").arr should have size 0
    json("game")("currentPlayer").str shouldBe "White"
    json("game")("board").arr should have size 32
  }

  it should "create a new session from PGN without a result token" in {
    val fixture = makeFixture()
    val body = """{"format":"PGN","notation":"1. e4 e5"}"""

    val resp = run(
      fixture.gameRoutes,
      Request[IO](Method.POST, uri"/sessions/import-notation").withBodyStream(jsonBody(body))
    )

    resp.status shouldBe Status.Created
    val json = bodyJson(resp)
    json("game")("moveHistory").arr should have size 2
  }

  it should "return 400 for PGN with an illegal first move" in {
    val fixture = makeFixture()
    val body = """{"format":"PGN","notation":"1. e5 *"}"""

    val resp = run(
      fixture.gameRoutes,
      Request[IO](Method.POST, uri"/sessions/import-notation").withBodyStream(jsonBody(body))
    )

    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "INVALID_NOTATION"
  }

  it should "create a session with lifecycle Created regardless of how many moves are in the PGN" in {
    val fixture = makeFixture()
    val body = """{"format":"PGN","notation":"1. e4 e5 2. Nf3 Nc6 *"}"""

    val resp = run(
      fixture.gameRoutes,
      Request[IO](Method.POST, uri"/sessions/import-notation").withBodyStream(jsonBody(body))
    )

    resp.status shouldBe Status.Created
    val json = bodyJson(resp)
    json("session")("lifecycle").str shouldBe "Created"
    json("game")("moveHistory").arr should have size 4
  }

  // Integration tests: notation routes wired through the full Http4sApp composed router.
  // These guard against Http4sApp wiring regressions that the isolation tests above cannot detect.

  private def makeApp(): HttpApp[IO] =
    val sessionRepo = InMemorySessionRepository()
    val gameRepo    = InMemoryGameRepository()
    val store       = InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionLifecycleService = SessionLifecycleService(sessionRepo, _ => ())
    val commands    = SessionGameCommandService(sessionLifecycleService, store, _ => ())
    val gameService = DefaultGameService(commands, sessionLifecycleService, gameRepo, _ => ())
    val persistentSessionService =
      PersistentSessionService(sessionRepo, gameRepo, store, sessionLifecycleService)
    val snapshotTransferService =
      SessionSnapshotTransferService(persistentSessionService, store)
    Http4sApp(gameService, persistentSessionService, snapshotTransferService, gameRepo, store).httpApp

  private def createGameIdViaApp(app: HttpApp[IO]): String =
    val response =
      run(app, Request[IO](Method.POST, uri"/sessions").withBodyStream(jsonBody("{}")))
    bodyJson(response)("session")("gameId").str

  "Http4sApp composed routes: GET /games/{gameId}/notation/fen" should "return FEN for a game created through the full app" in {
    val app    = makeApp()
    val gameId = createGameIdViaApp(app)

    val resp =
      run(app, Request[IO](Method.GET, Uri.unsafeFromString(s"/games/$gameId/notation/fen")))

    resp.status shouldBe Status.Ok
    val json = bodyJson(resp)
    json("format").str shouldBe "FEN"
    json("notation").str should include(" w ")
  }

  "Http4sApp composed routes: GET /games/{gameId}/notation/pgn" should "return PGN for a game created through the full app" in {
    val app    = makeApp()
    val gameId = createGameIdViaApp(app)

    val resp =
      run(app, Request[IO](Method.GET, Uri.unsafeFromString(s"/games/$gameId/notation/pgn")))

    resp.status shouldBe Status.Ok
    val json = bodyJson(resp)
    json("format").str shouldBe "PGN"
    json("notation").str should not be empty
  }

  "Http4sApp composed routes: PGN round-trip" should "import a PGN exported from a played game and recover the same position" in {
    val app    = makeApp()
    val gameId = createGameIdViaApp(app)

    // Play 1. e4 e5 through the full app
    def submitMove(from: String, to: String): Unit =
      run(
        app,
        Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
          .withBodyStream(jsonBody(s"""{"from":"$from","to":"$to"}"""))
      )
    submitMove("e2", "e4")
    submitMove("e7", "e5")

    // Export PGN from that game
    val pgnResp =
      run(app, Request[IO](Method.GET, Uri.unsafeFromString(s"/games/$gameId/notation/pgn")))
    pgnResp.status shouldBe Status.Ok
    val pgn = bodyJson(pgnResp)("notation").str

    // Import the exported PGN into a new session
    val importBody = s"""{"format":"PGN","notation":${ujson.Str(pgn)}}"""
    val importResp = run(
      app,
      Request[IO](Method.POST, uri"/sessions/import-notation").withBodyStream(jsonBody(importBody))
    )

    importResp.status shouldBe Status.Created
    val imported = bodyJson(importResp)
    imported("game")("moveHistory").arr should have size 2
    imported("game")("currentPlayer").str shouldBe "White"
    imported("game")("board").arr should have size 32
    // Imported game has a fresh session id, not the original
    imported("session")("sessionId").str should not be gameId
  }
