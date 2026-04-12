package chess.adapter.http4s

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.adapter.http4s.route.{Http4sGameRoutes, Http4sSessionRoutes}
import chess.adapter.repository.{InMemoryGameRepository, InMemorySessionRepository}
import chess.application.session.model.SessionIds.GameId
import chess.application.session.service.SessionService
import chess.domain.model.{Board, Color, Piece, PieceType, Position}
import chess.domain.state.{GameStateFactory, CastlingRights}
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.util.UUID

/** In-memory tests for [[Http4sGameRoutes]].
 *
 *  All game-route behaviours are exercised without a network socket.
 *  The fixture wires real in-memory repositories through real application
 *  services so the tests validate the full adapter→application path.
 */
class Http4sGameRoutesSpec extends AnyFlatSpec with Matchers:

  // ── shared fixtures ────────────────────────────────────────────────────────

  private case class TestFixture(
    gameRoutes: HttpApp[IO],
    sessRoutes: HttpApp[IO],
    gameRepo:   InMemoryGameRepository,
    service:    SessionService
  )

  /** Returns a fixture with shared in-memory repositories. */
  private def makeFixture(): TestFixture =
    val sessionRepo = InMemorySessionRepository()
    val gameRepo    = InMemoryGameRepository()
    val service     = SessionService(sessionRepo, _ => ())
    val gameRoutes  = Http4sGameRoutes(service, gameRepo).routes.orNotFound
    val sessRoutes  = Http4sSessionRoutes(service, gameRepo).routes.orNotFound
    TestFixture(gameRoutes, sessRoutes, gameRepo, service)

  /** Convenience alias kept for existing tests. */
  private def makeRoutes() =
    val f = makeFixture()
    (f.gameRoutes, f.sessRoutes)

  private def run(routes: HttpApp[IO], req: Request[IO]): Response[IO] =
    routes.run(req).unsafeRunSync()

  private def bodyJson(resp: Response[IO]): ujson.Value =
    ujson.read(resp.bodyText.compile.string.unsafeRunSync())

  private def jsonBody(s: String) =
    fs2.Stream.emits(s.getBytes("UTF-8")).covary[IO]

  /** Create a fresh session; return the gameId string. */
  private def createSession(sessRoutes: HttpApp[IO]): String =
    val req  = Request[IO](Method.POST, uri"/sessions").withBodyStream(jsonBody("{}"))
    val json = bodyJson(run(sessRoutes, req))
    json("session")("gameId").str

  // ── GET /games/{gameId} ────────────────────────────────────────────────────

  "GET /games/{gameId}" should "return 200 with the initial game state" in {
    val (gameRoutes, sessRoutes) = makeRoutes()
    val gameId = createSession(sessRoutes)

    val resp = run(gameRoutes, Request[IO](Method.GET, Uri.unsafeFromString(s"/games/$gameId")))
    resp.status                    shouldBe Status.Ok
    val json = bodyJson(resp)
    json("gameId").str             shouldBe gameId
    json("currentPlayer").str      shouldBe "White"
    json("status").str             shouldBe "Ongoing"
    json("inCheck").bool           shouldBe false
    json("board").arr              should have size 32
  }

  it should "return 404 for an unknown game id" in {
    val (gameRoutes, _) = makeRoutes()
    val unknown = UUID.randomUUID().toString
    val resp    = run(gameRoutes, Request[IO](Method.GET, Uri.unsafeFromString(s"/games/$unknown")))
    resp.status                  shouldBe Status.NotFound
    bodyJson(resp)("code").str   shouldBe "GAME_NOT_FOUND"
  }

  it should "return 400 for a non-UUID game id" in {
    val (gameRoutes, _) = makeRoutes()
    val resp = run(gameRoutes, Request[IO](Method.GET, uri"/games/not-a-uuid"))
    resp.status                  shouldBe Status.BadRequest
    bodyJson(resp)("code").str   shouldBe "BAD_REQUEST"
  }

  it should "return a non-empty legalTargetsByFrom for the initial position" in {
    val (gameRoutes, sessRoutes) = makeRoutes()
    val gameId = createSession(sessRoutes)

    val resp = run(gameRoutes, Request[IO](Method.GET, Uri.unsafeFromString(s"/games/$gameId")))
    resp.status shouldBe Status.Ok
    val legal = bodyJson(resp)("legalTargetsByFrom").obj
    // White has 16 pawns moves + 4 knight moves = 20 moves across 10 source squares
    legal should not be empty
    // e2 pawn should be able to reach e3 and e4
    legal("e2").arr.map(_.str).toSet should contain allOf ("e3", "e4")
  }

  it should "return an empty moveHistory and null lastMove for a fresh game" in {
    val (gameRoutes, sessRoutes) = makeRoutes()
    val gameId = createSession(sessRoutes)

    val resp = run(gameRoutes, Request[IO](Method.GET, Uri.unsafeFromString(s"/games/$gameId")))
    resp.status shouldBe Status.Ok
    val json = bodyJson(resp)
    json("moveHistory").arr  shouldBe empty
    json("lastMove").isNull  shouldBe true
  }

  // ── POST /games/{gameId}/moves ─────────────────────────────────────────────

  "POST /games/{gameId}/moves" should "apply a legal opening move and return the updated state" in {
    val (gameRoutes, sessRoutes) = makeRoutes()
    val gameId = createSession(sessRoutes)

    val req  = Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
      .withBodyStream(jsonBody("""{"from":"e2","to":"e4"}"""))
    val resp = run(gameRoutes, req)
    resp.status                              shouldBe Status.Ok
    val json = bodyJson(resp)
    json("game")("currentPlayer").str        shouldBe "Black"
    json("game")("board").arr                should have size 32
    json("sessionLifecycle").str             shouldBe "Active"
  }

  it should "persist the updated state so a subsequent GET reflects the move" in {
    val (gameRoutes, sessRoutes) = makeRoutes()
    val gameId = createSession(sessRoutes)

    val moveReq = Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
      .withBodyStream(jsonBody("""{"from":"e2","to":"e4"}"""))
    run(gameRoutes, moveReq)

    val getResp = run(gameRoutes, Request[IO](Method.GET, Uri.unsafeFromString(s"/games/$gameId")))
    getResp.status                           shouldBe Status.Ok
    bodyJson(getResp)("currentPlayer").str   shouldBe "Black"
  }

  it should "return 400 for a body with missing required fields" in {
    val (gameRoutes, sessRoutes) = makeRoutes()
    val gameId = createSession(sessRoutes)

    val req  = Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
      .withBodyStream(jsonBody("""{"invalid":"payload"}"""))
    val resp = run(gameRoutes, req)
    resp.status                  shouldBe Status.BadRequest
    bodyJson(resp)("code").str   shouldBe "BAD_REQUEST"
  }

  it should "return 400 for unparseable JSON" in {
    val (gameRoutes, sessRoutes) = makeRoutes()
    val gameId = createSession(sessRoutes)

    val req  = Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
      .withBodyStream(jsonBody("not json at all"))
    val resp = run(gameRoutes, req)
    resp.status                  shouldBe Status.BadRequest
    bodyJson(resp)("code").str   shouldBe "BAD_REQUEST"
  }

  it should "return 422 for an illegal chess move" in {
    val (gameRoutes, sessRoutes) = makeRoutes()
    val gameId = createSession(sessRoutes)

    // A pawn cannot advance three squares.
    val req  = Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
      .withBodyStream(jsonBody("""{"from":"e2","to":"e5"}"""))
    val resp = run(gameRoutes, req)
    resp.status                  shouldBe Status.UnprocessableEntity
    bodyJson(resp)("code").str   shouldBe "ILLEGAL_MOVE"
  }

  it should "return 422 for an invalid square in the 'from' field" in {
    val (gameRoutes, sessRoutes) = makeRoutes()
    val gameId = createSession(sessRoutes)

    val req  = Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
      .withBodyStream(jsonBody("""{"from":"z9","to":"e4"}"""))
    val resp = run(gameRoutes, req)
    resp.status                  shouldBe Status.UnprocessableEntity
    bodyJson(resp)("code").str   shouldBe "INVALID_MOVE"
  }

  it should "return 422 for an invalid promotion piece type" in {
    val (gameRoutes, sessRoutes) = makeRoutes()
    val gameId = createSession(sessRoutes)

    // King is not a legal promotion target.
    val req  = Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
      .withBodyStream(jsonBody("""{"from":"e2","to":"e4","promotion":"King"}"""))
    val resp = run(gameRoutes, req)
    resp.status                  shouldBe Status.UnprocessableEntity
    bodyJson(resp)("code").str   shouldBe "INVALID_MOVE"
  }

  it should "return 404 for an unknown game id" in {
    val (gameRoutes, _) = makeRoutes()
    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/games/${UUID.randomUUID()}/moves"))
      .withBodyStream(jsonBody("""{"from":"e2","to":"e4"}"""))
    val resp = run(gameRoutes, req)
    resp.status                  shouldBe Status.NotFound
    bodyJson(resp)("code").str   shouldBe "GAME_NOT_FOUND"
  }

  it should "update moveHistory and lastMove after a move" in {
    val (gameRoutes, sessRoutes) = makeRoutes()
    val gameId = createSession(sessRoutes)

    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
      .withBodyStream(jsonBody("""{"from":"e2","to":"e4"}"""))
    val json = bodyJson(run(gameRoutes, req))("game")

    val history = json("moveHistory").arr
    history                    should have size 1
    history(0)("from").str     shouldBe "e2"
    history(0)("to").str       shouldBe "e4"

    val last = json("lastMove")
    last("from").str           shouldBe "e2"
    last("to").str             shouldBe "e4"
    last("promotion").isNull   shouldBe true
  }

  it should "return PROMOTION_REQUIRED (422) when a pawn reaches the 8th rank without a promotion piece" in {
    val fixture = makeFixture()
    val gameId  = createSession(fixture.sessRoutes)

    // Set up a position where White has a pawn on e7 ready to promote.
    // Both kings are placed on safe squares; all other pieces removed.
    val pos = (alg: String) => Position.fromAlgebraic(alg).getOrElse(throw AssertionError(s"bad: $alg"))
    val promotionBoard = Board.empty
      .place(pos("e7"), Piece(Color.White, PieceType.Pawn))
      .place(pos("a1"), Piece(Color.White, PieceType.King))
      .place(pos("h8"), Piece(Color.Black, PieceType.King))
    val promotionState = GameStateFactory.initial().copy(
      board          = promotionBoard,
      currentPlayer  = Color.White,
      moveHistory    = Nil,
      castlingRights = CastlingRights.none
    )
    fixture.gameRepo.save(GameId(UUID.fromString(gameId)), promotionState)

    // Move the pawn to e8 without specifying a promotion piece.
    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
      .withBodyStream(jsonBody("""{"from":"e7","to":"e8"}"""))
    val resp = run(fixture.gameRoutes, req)
    resp.status                shouldBe Status.UnprocessableEntity
    bodyJson(resp)("code").str shouldBe "PROMOTION_REQUIRED"
  }

  it should "return GAME_FINISHED (409) when the session is already finished" in {
    val fixture = makeFixture()
    val gameId  = createSession(fixture.sessRoutes)

    // Force the session lifecycle to Finished by having Black resign (no resign endpoint
    // exists yet, so we drive it to checkmate via Scholar's mate).
    // Scholar's mate: 1.e4 e5  2.Bc4 Nc6  3.Qh5 Nf6??  4.Qxf7#
    val moves = List(
      """{"from":"e2","to":"e4"}""",
      """{"from":"e7","to":"e5"}""",
      """{"from":"f1","to":"c4"}""",
      """{"from":"b8","to":"c6"}""",
      """{"from":"d1","to":"h5"}""",
      """{"from":"g8","to":"f6"}""",
      """{"from":"h5","to":"f7"}"""
    )
    moves.foreach { body =>
      run(fixture.gameRoutes,
        Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
          .withBodyStream(jsonBody(body)))
    }

    // Any further move on the finished game should be rejected with GAME_FINISHED.
    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
      .withBodyStream(jsonBody("""{"from":"e7","to":"e5"}"""))
    val resp = run(fixture.gameRoutes, req)
    resp.status                shouldBe Status.Conflict
    bodyJson(resp)("code").str shouldBe "GAME_FINISHED"
  }

