package chess.adapter.http4s

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.adapter.http4s.route.Http4sSessionRoutes
import chess.adapter.repository.{
  InMemoryGameRepository,
  InMemorySessionGameStore,
  InMemorySessionRepository
}
import chess.application.DefaultGameService
import chess.application.port.repository.RepositoryError
import chess.application.session.model.{SessionMode, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.application.session.service.{
  PersistentSessionAggregate,
  PersistentSessionError,
  PersistentSessionService,
  SessionGameCommandService,
  SessionLifecycleService
}
import chess.domain.state.GameStateFactory
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.util.UUID

/** In-memory tests for [[Http4sSessionRoutes]].
  *
  * Routes are exercised via `routes.orNotFound.run(request).unsafeRunSync()` — no network socket is
  * involved. This keeps the tests fast, deterministic, and fully covered by scoverage (unlike the
  * JDK adapter's route classes, which required a live server and were excluded from
  * instrumentation).
  *
  * The fixture now wires routes through [[DefaultGameService]] (the
  * [[chess.application.GameServiceApi]] implementation), replacing the previous three-dependency
  * split.
  */
class Http4sSessionRoutesSpec extends AnyFlatSpec with Matchers with EitherValues:

  // ── shared fixtures ────────────────────────────────────────────────────────

  private def makeRoutes() =
    val sessionRepo = InMemorySessionRepository()
    val gameRepo = InMemoryGameRepository()
    val store = InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionLifecycleService = SessionLifecycleService(sessionRepo, _ => ())
    val persistentSessionService =
      PersistentSessionService(sessionRepo, gameRepo, store, sessionLifecycleService)
    val svc = SessionGameCommandService(sessionLifecycleService, store, _ => ())
    val gameService = DefaultGameService(svc, sessionLifecycleService, gameRepo, _ => ())
    Http4sSessionRoutes(gameService, persistentSessionService).routes.orNotFound

  /** Run a request through the route under test and return the response. */
  private def run(routes: HttpApp[IO], req: Request[IO]): Response[IO] =
    routes.run(req).unsafeRunSync()

  private def bodyJson(resp: Response[IO]): ujson.Value =
    ujson.read(resp.bodyText.compile.string.unsafeRunSync())

  // ── POST /sessions ─────────────────────────────────────────────────────────

  "POST /sessions" should "return 201 with bundled session and initial game state" in {
    val routes = makeRoutes()
    val req = Request[IO](Method.POST, uri"/sessions")
      .withBodyStream(fs2.Stream.emits("{}".getBytes("UTF-8")).covary[IO])

    val resp = run(routes, req)
    resp.status shouldBe Status.Created
    val json = bodyJson(resp)
    json("session")("sessionId").str should not be empty
    json("session")("lifecycle").str shouldBe "Created"
    json("session")("mode").str shouldBe "HumanVsHuman"
    json("game")("currentPlayer").str shouldBe "White"
    json("game")("status").str shouldBe "Ongoing"
    json("game")("board").arr should have size 32
  }

  it should "accept explicit mode and controller fields" in {
    val routes = makeRoutes()
    val body =
      """{"mode":"HumanVsHuman","whiteController":"HumanLocal","blackController":"HumanLocal"}"""
    val req = Request[IO](Method.POST, uri"/sessions")
      .withBodyStream(fs2.Stream.emits(body.getBytes("UTF-8")).covary[IO])

    val resp = run(routes, req)
    resp.status shouldBe Status.Created
    val json = bodyJson(resp)
    json("session")("mode").str shouldBe "HumanVsHuman"
    json("session")("whiteController").str shouldBe "HumanLocal"
    json("session")("blackController").str shouldBe "HumanLocal"
  }

  it should "derive the AI side from HumanVsAI mode" in {
    val routes = makeRoutes()
    val body = """{"mode":"HumanVsAI"}"""
    val req = Request[IO](Method.POST, uri"/sessions")
      .withBodyStream(fs2.Stream.emits(body.getBytes("UTF-8")).covary[IO])

    val resp = run(routes, req)
    resp.status shouldBe Status.Created
    val json = bodyJson(resp)
    json("session")("mode").str shouldBe "HumanVsAI"
    json("session")("whiteController").str shouldBe "HumanLocal"
    json("session")("blackController").str shouldBe "AI"
  }

  it should "allow a human controller override for the HumanVsAI human side" in {
    val routes = makeRoutes()
    val body = """{"mode":"HumanVsAI","whiteController":"HumanRemote"}"""
    val req = Request[IO](Method.POST, uri"/sessions")
      .withBodyStream(fs2.Stream.emits(body.getBytes("UTF-8")).covary[IO])

    val resp = run(routes, req)
    resp.status shouldBe Status.Created
    val json = bodyJson(resp)
    json("session")("whiteController").str shouldBe "HumanRemote"
    json("session")("blackController").str shouldBe "AI"
  }

  it should "derive both AI sides from AIVsAI mode" in {
    val routes = makeRoutes()
    val body = """{"mode":"AIVsAI"}"""
    val req = Request[IO](Method.POST, uri"/sessions")
      .withBodyStream(fs2.Stream.emits(body.getBytes("UTF-8")).covary[IO])

    val resp = run(routes, req)
    resp.status shouldBe Status.Created
    val json = bodyJson(resp)
    json("session")("mode").str shouldBe "AIVsAI"
    json("session")("whiteController").str shouldBe "AI"
    json("session")("blackController").str shouldBe "AI"
  }

  it should "return 400 for an unknown mode value" in {
    val routes = makeRoutes()
    val body = """{"mode":"BattleChess"}"""
    val req = Request[IO](Method.POST, uri"/sessions")
      .withBodyStream(fs2.Stream.emits(body.getBytes("UTF-8")).covary[IO])

    val resp = run(routes, req)
    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "BAD_REQUEST"
  }

  // REST v1 contract: AI seats are derived from mode. Clients may not configure
  // AI controllers or engine identity via controller fields.
  it should "return 400 when whiteController is AI (not valid in REST v1)" in {
    val routes = makeRoutes()
    val body = """{"mode":"HumanVsAI","whiteController":"AI"}"""
    val req = Request[IO](Method.POST, uri"/sessions")
      .withBodyStream(fs2.Stream.emits(body.getBytes("UTF-8")).covary[IO])

    val resp = run(routes, req)
    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "BAD_REQUEST"
  }

  it should "return 400 when HumanVsAI tries to override the server AI side" in {
    val routes = makeRoutes()
    val body = """{"mode":"HumanVsAI","blackController":"HumanLocal"}"""
    val req = Request[IO](Method.POST, uri"/sessions")
      .withBodyStream(fs2.Stream.emits(body.getBytes("UTF-8")).covary[IO])

    val resp = run(routes, req)
    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "BAD_REQUEST"
  }

  it should "return 400 when AIVsAI includes controller overrides" in {
    val routes = makeRoutes()
    val body = """{"mode":"AIVsAI","whiteController":"HumanLocal"}"""
    val req = Request[IO](Method.POST, uri"/sessions")
      .withBodyStream(fs2.Stream.emits(body.getBytes("UTF-8")).covary[IO])

    val resp = run(routes, req)
    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "BAD_REQUEST"
  }

  it should "return 400 for malformed JSON" in {
    val routes = makeRoutes()
    val req = Request[IO](Method.POST, uri"/sessions")
      .withBodyStream(fs2.Stream.emits("not json".getBytes("UTF-8")).covary[IO])

    val resp = run(routes, req)
    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "BAD_REQUEST"
  }

  // ── GET /sessions ──────────────────────────────────────────────────────────

  "GET /sessions" should "return 200 with empty list when no sessions exist" in {
    val routes = makeRoutes()
    val resp = run(routes, Request[IO](Method.GET, uri"/sessions"))
    resp.status shouldBe Status.Ok
    bodyJson(resp)("sessions").arr shouldBe empty
  }

  it should "return the active session after creation" in {
    val routes = makeRoutes()
    val createReq = Request[IO](Method.POST, uri"/sessions")
      .withBodyStream(fs2.Stream.emits("{}".getBytes("UTF-8")).covary[IO])
    run(routes, createReq)

    val resp = run(routes, Request[IO](Method.GET, uri"/sessions"))
    resp.status shouldBe Status.Ok
    bodyJson(resp)("sessions").arr should have size 1
  }

  // ── GET /sessions/{sessionId} ──────────────────────────────────────────────

  "GET /sessions/{sessionId}" should "return 200 with session data after creation" in {
    val routes = makeRoutes()

    // create a session first
    val createReq = Request[IO](Method.POST, uri"/sessions")
      .withBodyStream(fs2.Stream.emits("{}".getBytes("UTF-8")).covary[IO])
    val createJson = bodyJson(run(routes, createReq))
    val sessionId = createJson("session")("sessionId").str

    // now retrieve it
    val getResp =
      run(routes, Request[IO](Method.GET, Uri.unsafeFromString(s"/sessions/$sessionId")))
    getResp.status shouldBe Status.Ok
    val json = bodyJson(getResp)
    json("sessionId").str shouldBe sessionId
    json("lifecycle").str shouldBe "Created"
  }

  it should "return 404 for an unknown session id" in {
    val routes = makeRoutes()
    val unknown = UUID.randomUUID().toString
    val resp = run(routes, Request[IO](Method.GET, Uri.unsafeFromString(s"/sessions/$unknown")))
    resp.status shouldBe Status.NotFound
    bodyJson(resp)("code").str shouldBe "SESSION_NOT_FOUND"
  }

  it should "return 400 for a non-UUID session id" in {
    val routes = makeRoutes()
    val resp = run(routes, Request[IO](Method.GET, uri"/sessions/not-a-uuid"))
    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "BAD_REQUEST"
  }

  "GET /sessions/{sessionId}/state" should "return 200 with session and game state after creation" in {
    val routes = makeRoutes()
    val createReq = Request[IO](Method.POST, uri"/sessions")
      .withBodyStream(fs2.Stream.emits("{}".getBytes("UTF-8")).covary[IO])
    val createJson = bodyJson(run(routes, createReq))
    val sessionId = createJson("session")("sessionId").str

    val getResp =
      run(routes, Request[IO](Method.GET, Uri.unsafeFromString(s"/sessions/$sessionId/state")))
    getResp.status shouldBe Status.Ok
    val json = bodyJson(getResp)
    json("session")("sessionId").str shouldBe sessionId
    json("game")("currentPlayer").str shouldBe "White"
    json("game")("status").str shouldBe "Ongoing"
  }

  it should "return 404 for an unknown session id" in {
    val routes = makeRoutes()
    val unknown = UUID.randomUUID().toString
    val resp =
      run(routes, Request[IO](Method.GET, Uri.unsafeFromString(s"/sessions/$unknown/state")))
    resp.status shouldBe Status.NotFound
    bodyJson(resp)("code").str shouldBe "SESSION_NOT_FOUND"
  }

  it should "return 400 for a non-UUID session id" in {
    val routes = makeRoutes()
    val resp = run(routes, Request[IO](Method.GET, uri"/sessions/not-a-uuid/state"))
    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "BAD_REQUEST"
  }

  it should "return 500 when the session exists but the game state is missing" in {
    val sessionRepo = InMemorySessionRepository()
    val gameRepo = InMemoryGameRepository()
    val store = InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionLifecycleService = SessionLifecycleService(sessionRepo, _ => ())
    val persistentSessionService =
      PersistentSessionService(sessionRepo, gameRepo, store, sessionLifecycleService)
    val svc = SessionGameCommandService(sessionLifecycleService, store, _ => ())
    val gameService = DefaultGameService(svc, sessionLifecycleService, gameRepo, _ => ())
    val routes = Http4sSessionRoutes(gameService, persistentSessionService).routes.orNotFound

    val session = sessionLifecycleService
      .createSession(
        gameId = GameId.random(),
        mode = SessionMode.HumanVsHuman,
        whiteController = SideController.HumanLocal,
        blackController = SideController.HumanLocal
      )
      .value

    val resp = run(
      routes,
      Request[IO](Method.GET, Uri.unsafeFromString(s"/sessions/${session.sessionId.value}/state"))
    )
    resp.status shouldBe Status.InternalServerError
    bodyJson(resp)("code").str shouldBe "INTERNAL_ERROR"
  }

  "PUT /sessions/{sessionId}/state" should "return 200 with the saved aggregate" in {
    val routes = makeRoutes()
    val createReq = Request[IO](Method.POST, uri"/sessions")
      .withBodyStream(fs2.Stream.emits("{}".getBytes("UTF-8")).covary[IO])
    val created = bodyJson(run(routes, createReq))
    val sessionId = created("session")("sessionId").str
    val stateJson = bodyJson(
      run(routes, Request[IO](Method.GET, Uri.unsafeFromString(s"/sessions/$sessionId/state")))
    )

    val putResp = run(
      routes,
      Request[IO](Method.PUT, Uri.unsafeFromString(s"/sessions/$sessionId/state"))
        .withBodyStream(fs2.Stream.emits(stateJson.render().getBytes("UTF-8")).covary[IO])
    )

    putResp.status shouldBe Status.Ok
    val json = bodyJson(putResp)
    json("session")("sessionId").str shouldBe sessionId
    json("game")("gameId").str shouldBe created("game")("gameId").str
    json("castlingRights")("whiteKingSide").bool shouldBe true
  }

  it should "return 400 for a non-UUID path session id" in {
    val routes = makeRoutes()
    val body =
      """{"session":{"sessionId":"00000000-0000-0000-0000-000000000001","gameId":"00000000-0000-0000-0000-000000000002","mode":"HumanVsHuman","lifecycle":"Created","whiteController":"HumanLocal","blackController":"HumanLocal","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"},"game":{"gameId":"00000000-0000-0000-0000-000000000002","currentPlayer":"White","status":"Ongoing","inCheck":false,"winner":null,"drawReason":null,"fullmoveNumber":1,"halfmoveClock":0,"board":[],"moveHistory":[],"lastMove":null,"promotionPending":false,"legalTargetsByFrom":{}},"castlingRights":{"whiteKingSide":true,"whiteQueenSide":true,"blackKingSide":true,"blackQueenSide":true},"enPassant":null}"""
    val resp = run(
      routes,
      Request[IO](Method.PUT, uri"/sessions/not-a-uuid/state")
        .withBodyStream(fs2.Stream.emits(body.getBytes("UTF-8")).covary[IO])
    )
    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "BAD_REQUEST"
  }

  it should "return 400 for malformed JSON" in {
    val routes = makeRoutes()
    val resp = run(
      routes,
      Request[IO](Method.PUT, uri"/sessions/not-a-real-id/state")
        .withBodyStream(fs2.Stream.emits("not json".getBytes("UTF-8")).covary[IO])
    )
    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "BAD_REQUEST"
  }

  it should "return 400 for a path/body session id mismatch" in {
    val routes = makeRoutes()
    val createReq = Request[IO](Method.POST, uri"/sessions")
      .withBodyStream(fs2.Stream.emits("{}".getBytes("UTF-8")).covary[IO])
    val created = bodyJson(run(routes, createReq))
    val sessionId = created("session")("sessionId").str
    val stateJson = bodyJson(
      run(routes, Request[IO](Method.GET, Uri.unsafeFromString(s"/sessions/$sessionId/state")))
    )
    stateJson("session")("sessionId") = ujson.Str(UUID.randomUUID().toString)

    val resp = run(
      routes,
      Request[IO](Method.PUT, Uri.unsafeFromString(s"/sessions/$sessionId/state"))
        .withBodyStream(fs2.Stream.emits(stateJson.render().getBytes("UTF-8")).covary[IO])
    )
    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "BAD_REQUEST"
  }

  it should "return 404 when the target session does not exist" in {
    val routes = makeRoutes()
    val unknownSessionId = UUID.randomUUID().toString
    val gameId = UUID.randomUUID().toString
    val body =
      s"""{"session":{"sessionId":"$unknownSessionId","gameId":"$gameId","mode":"HumanVsHuman","lifecycle":"Created","whiteController":"HumanLocal","blackController":"HumanLocal","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"},"game":{"gameId":"$gameId","currentPlayer":"White","status":"Ongoing","inCheck":false,"winner":null,"drawReason":null,"fullmoveNumber":1,"halfmoveClock":0,"board":[],"moveHistory":[],"lastMove":null,"promotionPending":false,"legalTargetsByFrom":{}},"castlingRights":{"whiteKingSide":true,"whiteQueenSide":true,"blackKingSide":true,"blackQueenSide":true},"enPassant":null}"""
    val resp = run(
      routes,
      Request[IO](Method.PUT, Uri.unsafeFromString(s"/sessions/$unknownSessionId/state"))
        .withBodyStream(fs2.Stream.emits(body.getBytes("UTF-8")).covary[IO])
    )
    resp.status shouldBe Status.NotFound
    bodyJson(resp)("code").str shouldBe "SESSION_NOT_FOUND"
  }

  it should "return 409 when PersistentSessionService reports a conflict" in {
    val sessionRepo = InMemorySessionRepository()
    val gameRepo = InMemoryGameRepository()
    val store = InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionLifecycleService = SessionLifecycleService(sessionRepo, _ => ())
    val realPersistentSessionService =
      PersistentSessionService(sessionRepo, gameRepo, store, sessionLifecycleService)
    val conflictingPersistentSessionService = new PersistentSessionService(
      sessionRepo,
      gameRepo,
      store,
      sessionLifecycleService
    ):
      override def saveAggregate(
          sessionId: chess.application.session.model.SessionIds.SessionId,
          aggregate: PersistentSessionAggregate
      ): Either[PersistentSessionError, PersistentSessionAggregate] =
        Left(PersistentSessionError.Conflict("write conflict"))
    val svc = SessionGameCommandService(sessionLifecycleService, store, _ => ())
    val gameService = DefaultGameService(svc, sessionLifecycleService, gameRepo, _ => ())
    val routes =
      Http4sSessionRoutes(gameService, conflictingPersistentSessionService).routes.orNotFound

    val created = bodyJson(
      run(
        Http4sSessionRoutes(gameService, realPersistentSessionService).routes.orNotFound,
        Request[IO](Method.POST, uri"/sessions")
          .withBodyStream(fs2.Stream.emits("{}".getBytes("UTF-8")).covary[IO])
      )
    )
    val sessionId = created("session")("sessionId").str
    val stateJson = bodyJson(
      run(
        Http4sSessionRoutes(gameService, realPersistentSessionService).routes.orNotFound,
        Request[IO](Method.GET, Uri.unsafeFromString(s"/sessions/$sessionId/state"))
      )
    )

    val resp = run(
      routes,
      Request[IO](Method.PUT, Uri.unsafeFromString(s"/sessions/$sessionId/state"))
        .withBodyStream(fs2.Stream.emits(stateJson.render().getBytes("UTF-8")).covary[IO])
    )
    resp.status shouldBe Status.Conflict
    bodyJson(resp)("code").str shouldBe "CONFLICT"
  }

  it should "return 500 when PersistentSessionService reports storage failure" in {
    val sessionRepo = InMemorySessionRepository()
    val gameRepo = InMemoryGameRepository()
    val store = InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionLifecycleService = SessionLifecycleService(sessionRepo, _ => ())
    val realPersistentSessionService =
      PersistentSessionService(sessionRepo, gameRepo, store, sessionLifecycleService)
    val failingPersistentSessionService = new PersistentSessionService(
      sessionRepo,
      gameRepo,
      store,
      sessionLifecycleService
    ):
      override def saveAggregate(
          sessionId: chess.application.session.model.SessionIds.SessionId,
          aggregate: PersistentSessionAggregate
      ): Either[PersistentSessionError, PersistentSessionAggregate] =
        Left(PersistentSessionError.StorageFailure("disk full"))
    val svc = SessionGameCommandService(sessionLifecycleService, store, _ => ())
    val gameService = DefaultGameService(svc, sessionLifecycleService, gameRepo, _ => ())
    val createRoutes = Http4sSessionRoutes(gameService, realPersistentSessionService).routes.orNotFound
    val routes = Http4sSessionRoutes(gameService, failingPersistentSessionService).routes.orNotFound

    val created = bodyJson(
      run(
        createRoutes,
        Request[IO](Method.POST, uri"/sessions")
          .withBodyStream(fs2.Stream.emits("{}".getBytes("UTF-8")).covary[IO])
      )
    )
    val sessionId = created("session")("sessionId").str
    val stateJson = bodyJson(
      run(createRoutes, Request[IO](Method.GET, Uri.unsafeFromString(s"/sessions/$sessionId/state")))
    )

    val resp = run(
      routes,
      Request[IO](Method.PUT, Uri.unsafeFromString(s"/sessions/$sessionId/state"))
        .withBodyStream(fs2.Stream.emits(stateJson.render().getBytes("UTF-8")).covary[IO])
    )
    resp.status shouldBe Status.InternalServerError
    bodyJson(resp)("code").str shouldBe "INTERNAL_ERROR"
  }

  // ── POST /sessions/{id}/cancel ─────────────────────────────────────────────

  "POST /sessions/{id}/cancel" should "return 200 with Cancelled lifecycle after cancel" in {
    val routes = makeRoutes()
    val createReq = Request[IO](Method.POST, uri"/sessions")
      .withBodyStream(fs2.Stream.emits("{}".getBytes("UTF-8")).covary[IO])
    val createJson = bodyJson(run(routes, createReq))
    val sessionId = createJson("session")("sessionId").str

    val cancelResp =
      run(routes, Request[IO](Method.POST, Uri.unsafeFromString(s"/sessions/$sessionId/cancel")))
    cancelResp.status shouldBe Status.Ok
    bodyJson(cancelResp)("lifecycle").str shouldBe "Cancelled"
  }

  it should "return 409 when cancelling an already-finished session" in {
    val routes = makeRoutes()
    val createReq = Request[IO](Method.POST, uri"/sessions")
      .withBodyStream(fs2.Stream.emits("{}".getBytes("UTF-8")).covary[IO])
    val createJson = bodyJson(run(routes, createReq))
    val sessionId = createJson("session")("sessionId").str

    // cancel once — succeeds
    run(routes, Request[IO](Method.POST, Uri.unsafeFromString(s"/sessions/$sessionId/cancel")))

    // cancel again — session is now Cancelled
    val resp =
      run(routes, Request[IO](Method.POST, Uri.unsafeFromString(s"/sessions/$sessionId/cancel")))
    resp.status shouldBe Status.Conflict
    bodyJson(resp)("code").str shouldBe "SESSION_ALREADY_FINISHED"
  }

  it should "return 404 for an unknown session id" in {
    val routes = makeRoutes()
    val unknown = UUID.randomUUID().toString
    val resp =
      run(routes, Request[IO](Method.POST, Uri.unsafeFromString(s"/sessions/$unknown/cancel")))
    resp.status shouldBe Status.NotFound
    bodyJson(resp)("code").str shouldBe "SESSION_NOT_FOUND"
  }

  it should "return 400 for a non-UUID session id" in {
    val routes = makeRoutes()
    val resp = run(routes, Request[IO](Method.POST, uri"/sessions/not-a-uuid/cancel"))
    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "BAD_REQUEST"
  }

