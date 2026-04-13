package chess.adapter.http4s

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.adapter.http4s.route.{Http4sGameRoutes, Http4sSessionRoutes}
import chess.adapter.repository.{InMemoryGameRepository, InMemorySessionGameStore, InMemorySessionRepository}
import chess.application.event.AppEvent
import chess.application.port.event.EventPublisher
import chess.application.port.repository.{GameRepository, RepositoryError, SessionGameStore, SessionRepository}
import chess.application.session.model.GameSession
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.application.session.service.{SessionGameService, SessionService}
import chess.domain.state.GameState
import chess.domain.model.{Board, Color, Piece, PieceType, Position}
import chess.domain.state.{GameStateFactory, CastlingRights}
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.util.UUID
import scala.collection.mutable
import org.scalatest.EitherValues.*

/** In-memory tests for [[Http4sGameRoutes]].
 *
 *  All game-route behaviours are exercised without a network socket.
 *  The fixture wires real in-memory repositories through real application
 *  services so the tests validate the full adapter→application path.
 */
class Http4sGameRoutesSpec extends AnyFlatSpec with Matchers:

  // ── shared fixtures ────────────────────────────────────────────────────────

  private class TestEventPublisher extends EventPublisher:
    val events: mutable.ListBuffer[AppEvent] = mutable.ListBuffer.empty
    def publish(event: AppEvent): Unit = events += event

  private case class TestFixture(
    gameRoutes: HttpApp[IO],
    sessRoutes: HttpApp[IO],
    gameRepo:   InMemoryGameRepository,
    svc:        SessionGameService,
    collector:  TestEventPublisher
  )

  // ── repository / store stubs ───────────────────────────────────────────────

  /** Game repository that always returns StorageFailure for every operation. */
  private class FailingGameRepository extends GameRepository:
    override def save(gameId: GameId, state: GameState): Either[RepositoryError, Unit] =
      Left(RepositoryError.StorageFailure("disk full"))
    override def load(gameId: GameId): Either[RepositoryError, GameState] =
      Left(RepositoryError.StorageFailure("disk full"))

  /** Session game store that always fails on save (reads via underlying repos are unaffected). */
  private class FailingSessionGameStore extends SessionGameStore:
    override def save(session: GameSession, state: GameState): Either[RepositoryError, Unit] =
      Left(RepositoryError.StorageFailure("write failed"))

  /** Returns a fixture with shared in-memory repositories wired through SessionGameService. */
  private def makeFixture(collector: TestEventPublisher = new TestEventPublisher): TestFixture =
    val sessionRepo    = InMemorySessionRepository()
    val gameRepo       = InMemoryGameRepository()
    val store          = InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionService = SessionService(sessionRepo, _ => ())
    val svc            = SessionGameService(sessionService, store, collector)
    val gameRoutes     = Http4sGameRoutes(svc, sessionService, gameRepo).routes.orNotFound
    val sessRoutes     = Http4sSessionRoutes(svc, sessionService).routes.orNotFound
    TestFixture(gameRoutes, sessRoutes, gameRepo, svc, collector)

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

  // REST v1 two-step promotion contract: reject without piece → accept with piece.
  // sessionLifecycle in the success response must never be "AwaitingPromotion"
  // because the session is only updated after the complete move is committed.
  it should "accept a promotion move when the piece is included and return sessionLifecycle Active" in {
    val fixture = makeFixture()
    val gameId  = createSession(fixture.sessRoutes)

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

    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
      .withBodyStream(jsonBody("""{"from":"e7","to":"e8","promotion":"Queen"}"""))
    val resp = run(fixture.gameRoutes, req)
    resp.status                                        shouldBe Status.Ok
    val json = bodyJson(resp)
    json("sessionLifecycle").str                       shouldBe "Active"
    json("sessionLifecycle").str                       should not be "AwaitingPromotion"
    json("game")("board").arr.map(_("pieceType").str)  should contain ("Queen")
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

  // ── REST v1 controller validation ─────────────────────────────────────────
  // An explicitly invalid controller value must be rejected (400) rather than
  // silently falling back to HumanLocal.  An absent controller field defaults
  // to HumanLocal as before.

  it should "return 400 BAD_REQUEST for an explicitly invalid controller value" in {
    val (gameRoutes, sessRoutes) = makeRoutes()
    val gameId = createSession(sessRoutes)

    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
      .withBodyStream(jsonBody("""{"from":"e2","to":"e4","controller":"BogusController"}"""))
    val resp = run(gameRoutes, req)
    resp.status                shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "BAD_REQUEST"
  }

  it should "return 400 BAD_REQUEST when controller is AI (not valid in REST v1)" in {
    val (gameRoutes, sessRoutes) = makeRoutes()
    val gameId = createSession(sessRoutes)

    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
      .withBodyStream(jsonBody("""{"from":"e2","to":"e4","controller":"AI"}"""))
    val resp = run(gameRoutes, req)
    resp.status                shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "BAD_REQUEST"
  }

  it should "succeed when controller is omitted (defaults to HumanLocal)" in {
    val (gameRoutes, sessRoutes) = makeRoutes()
    val gameId = createSession(sessRoutes)

    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
      .withBodyStream(jsonBody("""{"from":"e2","to":"e4"}"""))
    val resp = run(gameRoutes, req)
    resp.status shouldBe Status.Ok
  }

  it should "succeed when controller is explicitly HumanLocal" in {
    val (gameRoutes, sessRoutes) = makeRoutes()
    val gameId = createSession(sessRoutes)

    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
      .withBodyStream(jsonBody("""{"from":"e2","to":"e4","controller":"HumanLocal"}"""))
    val resp = run(gameRoutes, req)
    resp.status shouldBe Status.Ok
  }

  // ── Unified application mutation boundary ─────────────────────────────────
  // These tests prove that a REST move now goes through SessionGameService —
  // the same unified boundary as GUI, TUI, and AI.

  "POST /games/{gameId}/moves (session boundary)" should "publish MoveApplied after a successful REST move" in {
    val collector = new TestEventPublisher
    val fixture   = makeFixture(collector)
    val gameId    = createSession(fixture.sessRoutes)
    collector.events.clear()  // discard SessionCreated from setup

    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
      .withBodyStream(jsonBody("""{"from":"e2","to":"e4"}"""))
    run(fixture.gameRoutes, req)

    val moveEvents = collector.events.collect { case e: AppEvent.MoveApplied => e }
    moveEvents should have size 1
    moveEvents.head.move.from.toString shouldBe "e2"
    moveEvents.head.move.to.toString   shouldBe "e4"
  }

  it should "not publish MoveApplied after an illegal REST move" in {
    val collector = new TestEventPublisher
    val fixture   = makeFixture(collector)
    val gameId    = createSession(fixture.sessRoutes)
    collector.events.clear()

    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
      .withBodyStream(jsonBody("""{"from":"e2","to":"e5"}"""))  // three-square jump: illegal
    run(fixture.gameRoutes, req)

    collector.events.collect { case e: AppEvent.MoveApplied => e } shouldBe empty
  }

  it should "persist game state to the repository after a successful REST move" in {
    val fixture = makeFixture()
    val gameId  = createSession(fixture.sessRoutes)
    val uuid    = UUID.fromString(gameId)

    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
      .withBodyStream(jsonBody("""{"from":"d2","to":"d4"}"""))
    run(fixture.gameRoutes, req)

    val savedState = fixture.gameRepo.load(GameId(uuid))
    savedState.isRight          shouldBe true
    savedState.value.moveHistory should have size 1
  }

  it should "publish GameFinished when a REST move delivers checkmate" in {
    val collector = new TestEventPublisher
    val fixture   = makeFixture(collector)
    val gameId    = createSession(fixture.sessRoutes)

    // Drive to Scholar's mate then verify GameFinished is published.
    val moves = List(
      """{"from":"e2","to":"e4"}""",
      """{"from":"e7","to":"e5"}""",
      """{"from":"f1","to":"c4"}""",
      """{"from":"b8","to":"c6"}""",
      """{"from":"d1","to":"h5"}""",
      """{"from":"g8","to":"f6"}"""
    )
    moves.foreach { body =>
      run(fixture.gameRoutes,
        Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
          .withBodyStream(jsonBody(body)))
    }
    collector.events.clear()

    run(fixture.gameRoutes,
      Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
        .withBodyStream(jsonBody("""{"from":"h5","to":"f7"}""")))

    val finishedEvents = collector.events.collect { case e: AppEvent.GameFinished => e }
    finishedEvents should have size 1
  }

  // ── GET storage failure path ───────────────────────────────────────────────

  "GET /games/{gameId} (storage failure)" should "return 500 INTERNAL_ERROR when game repository fails" in {
    val sessionRepo    = InMemorySessionRepository()
    val sessionService = SessionService(sessionRepo, _ => ())
    val store          = InMemorySessionGameStore(sessionRepo, InMemoryGameRepository())
    val svc            = SessionGameService(sessionService, store, _ => ())
    val failingRoutes  = Http4sGameRoutes(svc, sessionService, FailingGameRepository()).routes.orNotFound

    val resp = run(failingRoutes, Request[IO](Method.GET, Uri.unsafeFromString(s"/games/${UUID.randomUUID()}")))
    resp.status                shouldBe Status.InternalServerError
    bodyJson(resp)("code").str shouldBe "INTERNAL_ERROR"
  }

  // ── POST storage failure paths ─────────────────────────────────────────────

  "POST /games/{gameId}/moves (non-UUID id)" should "return 400 BAD_REQUEST" in {
    val (gameRoutes, _) = makeRoutes()
    val req = Request[IO](Method.POST, uri"/games/not-a-uuid/moves")
      .withBodyStream(jsonBody("""{"from":"e2","to":"e4"}"""))
    val resp = run(gameRoutes, req)
    resp.status                shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "BAD_REQUEST"
  }

  "POST /games/{gameId}/moves (storage failure)" should
      "return 500 INTERNAL_ERROR when gameRepository.load fails with StorageFailure" in {
    val sessionRepo    = InMemorySessionRepository()
    val sessionService = SessionService(sessionRepo, _ => ())
    val store          = InMemorySessionGameStore(sessionRepo, InMemoryGameRepository())
    val svc            = SessionGameService(sessionService, store, _ => ())
    val failingRoutes  = Http4sGameRoutes(svc, sessionService, FailingGameRepository()).routes.orNotFound

    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/games/${UUID.randomUUID()}/moves"))
      .withBodyStream(jsonBody("""{"from":"e2","to":"e4"}"""))
    val resp = run(failingRoutes, req)
    resp.status                shouldBe Status.InternalServerError
    bodyJson(resp)("code").str shouldBe "INTERNAL_ERROR"
  }

  it should "return 500 INTERNAL_ERROR when session store save fails after a valid move" in {
    // Create session + initial game state in real repos, then wire a failing store.
    val sessionRepo    = InMemorySessionRepository()
    val gameRepo       = InMemoryGameRepository()
    val normalStore    = InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionService = SessionService(sessionRepo, _ => ())
    val normalSvc      = SessionGameService(sessionService, normalStore, _ => ())
    val sessRoutes     = Http4sSessionRoutes(normalSvc, sessionService).routes.orNotFound
    val gameId         = createSession(sessRoutes)

    // Rebuild with a store that fails on every save.
    val failingSvc    = SessionGameService(sessionService, FailingSessionGameStore(), _ => ())
    val failingRoutes = Http4sGameRoutes(failingSvc, sessionService, gameRepo).routes.orNotFound

    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
      .withBodyStream(jsonBody("""{"from":"e2","to":"e4"}"""))
    val resp = run(failingRoutes, req)
    resp.status                shouldBe Status.InternalServerError
    bodyJson(resp)("code").str shouldBe "INTERNAL_ERROR"
  }

  // ── POST session lookup failure ────────────────────────────────────────────

  "POST /games/{gameId}/moves (session lookup failure)" should
      "return 404 GAME_NOT_FOUND when getSessionByGameId returns PersistenceFailed" in {
    // Create session + initial game state in real repos.
    val sessionRepo    = InMemorySessionRepository()
    val gameRepo       = InMemoryGameRepository()
    val normalStore    = InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionService = SessionService(sessionRepo, _ => ())
    val normalSvc      = SessionGameService(sessionService, normalStore, _ => ())
    val sessRoutes     = Http4sSessionRoutes(normalSvc, sessionService).routes.orNotFound
    val gameId         = createSession(sessRoutes)

    // Replace session repo with one that always fails on loadByGameId.
    val failingRepo = new SessionRepository:
      def save(s: GameSession): Either[RepositoryError, Unit]            = sessionRepo.save(s)
      def load(id: SessionId): Either[RepositoryError, GameSession]      = sessionRepo.load(id)
      def loadByGameId(id: GameId): Either[RepositoryError, GameSession] =
        Left(RepositoryError.StorageFailure("session db failure"))

    val failingSessionService = SessionService(failingRepo, _ => ())
    val svc           = SessionGameService(failingSessionService, normalStore, _ => ())
    val failingRoutes = Http4sGameRoutes(svc, failingSessionService, gameRepo).routes.orNotFound

    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
      .withBodyStream(jsonBody("""{"from":"e2","to":"e4"}"""))
    val resp = run(failingRoutes, req)
    resp.status                shouldBe Status.NotFound
    bodyJson(resp)("code").str shouldBe "GAME_NOT_FOUND"
  }

  // ── POST move error paths ──────────────────────────────────────────────────

  "POST /games/{gameId}/moves (move error paths)" should
      "return 403 UNAUTHORIZED_CONTROLLER when controller does not own the side to move" in {
    // Session has HumanLocal for both sides; HumanRemote is not authorised.
    val (gameRoutes, sessRoutes) = makeRoutes()
    val gameId = createSession(sessRoutes)

    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
      .withBodyStream(jsonBody("""{"from":"e2","to":"e4","controller":"HumanRemote"}"""))
    val resp = run(gameRoutes, req)
    resp.status                shouldBe Status.Forbidden
    bodyJson(resp)("code").str shouldBe "UNAUTHORIZED_CONTROLLER"
  }

  it should "return 422 NOT_YOUR_TURN when attempting to move the opponent's piece" in {
    // It is White's turn; e7 is a Black pawn — ChessService returns NotPlayersTurn.
    val (gameRoutes, sessRoutes) = makeRoutes()
    val gameId = createSession(sessRoutes)

    val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/games/$gameId/moves"))
      .withBodyStream(jsonBody("""{"from":"e7","to":"e5"}"""))
    val resp = run(gameRoutes, req)
    resp.status                shouldBe Status.UnprocessableEntity
    bodyJson(resp)("code").str shouldBe "NOT_YOUR_TURN"
  }
