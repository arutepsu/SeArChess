package chess.adapter.http4s

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.adapter.http4s.route.Http4sSessionRoutes
import chess.adapter.repository.{InMemoryGameRepository, InMemorySessionGameStore, InMemorySessionRepository}
import chess.application.session.service.{SessionGameService, SessionService, GameSessionCommands}
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.util.UUID

/** In-memory tests for [[Http4sSessionRoutes]].
 *
 *  Routes are exercised via `routes.orNotFound.run(request).unsafeRunSync()` —
 *  no network socket is involved.  This keeps the tests fast, deterministic,
 *  and fully covered by scoverage (unlike the JDK adapter's route classes,
 *  which required a live server and were excluded from instrumentation).
 */
class Http4sSessionRoutesSpec extends AnyFlatSpec with Matchers:

  // ── shared fixtures ────────────────────────────────────────────────────────

  private def makeRoutes() =
    val sessionRepo    = InMemorySessionRepository()
    val gameRepo       = InMemoryGameRepository()
    val store          = InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionService = SessionService(sessionRepo, _ => ())
    val svc            = SessionGameService(sessionService, store, _ => ())
    Http4sSessionRoutes(svc, sessionService).routes.orNotFound

  /** Run a request through the route under test and return the response. */
  private def run(routes: HttpApp[IO], req: Request[IO]): Response[IO] =
    routes.run(req).unsafeRunSync()

  private def bodyJson(resp: Response[IO]): ujson.Value =
    ujson.read(resp.bodyText.compile.string.unsafeRunSync())

  // ── POST /api/sessions ─────────────────────────────────────────────────────

  "POST /api/sessions" should "return 201 with bundled session and initial game state" in {
    val routes = makeRoutes()
    val req    = Request[IO](Method.POST, uri"/api/sessions")
      .withBodyStream(fs2.Stream.emits("{}".getBytes("UTF-8")).covary[IO])

    val resp = run(routes, req)
    resp.status                                   shouldBe Status.Created
    val json = bodyJson(resp)
    json("session")("sessionId").str              should not be empty
    json("session")("lifecycle").str              shouldBe "Created"
    json("session")("mode").str                   shouldBe "HumanVsHuman"
    json("game")("currentPlayer").str             shouldBe "White"
    json("game")("status").str                    shouldBe "Ongoing"
    json("game")("board").arr                     should have size 32
  }

  it should "accept explicit mode and controller fields" in {
    val routes = makeRoutes()
    val body   = """{"mode":"HumanVsHuman","whiteController":"HumanLocal","blackController":"HumanLocal"}"""
    val req    = Request[IO](Method.POST, uri"/api/sessions")
      .withBodyStream(fs2.Stream.emits(body.getBytes("UTF-8")).covary[IO])

    val resp = run(routes, req)
    resp.status                                         shouldBe Status.Created
    val json = bodyJson(resp)
    json("session")("mode").str                         shouldBe "HumanVsHuman"
    json("session")("whiteController").str              shouldBe "HumanLocal"
    json("session")("blackController").str              shouldBe "HumanLocal"
  }

  it should "return 400 for an unknown mode value" in {
    val routes = makeRoutes()
    val body   = """{"mode":"BattleChess"}"""
    val req    = Request[IO](Method.POST, uri"/api/sessions")
      .withBodyStream(fs2.Stream.emits(body.getBytes("UTF-8")).covary[IO])

    val resp = run(routes, req)
    resp.status             shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "BAD_REQUEST"
  }

  // REST v1 contract: "AI" is not a valid controller value.
  // AI engine wiring is server-side only; clients may not configure it via this endpoint.
  it should "return 400 when whiteController is AI (not valid in REST v1)" in {
    val routes = makeRoutes()
    val body   = """{"mode":"HumanVsAI","whiteController":"AI","blackController":"HumanLocal"}"""
    val req    = Request[IO](Method.POST, uri"/api/sessions")
      .withBodyStream(fs2.Stream.emits(body.getBytes("UTF-8")).covary[IO])

    val resp = run(routes, req)
    resp.status                  shouldBe Status.BadRequest
    bodyJson(resp)("code").str   shouldBe "BAD_REQUEST"
  }

  it should "return 400 for malformed JSON" in {
    val routes = makeRoutes()
    val req    = Request[IO](Method.POST, uri"/api/sessions")
      .withBodyStream(fs2.Stream.emits("not json".getBytes("UTF-8")).covary[IO])

    val resp = run(routes, req)
    resp.status             shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "BAD_REQUEST"
  }

  // ── GET /api/sessions/{sessionId} ─────────────────────────────────────────-

  "GET /api/sessions/{sessionId}" should "return 200 with session data after creation" in {
    val routes = makeRoutes()

    // create a session first
    val createReq = Request[IO](Method.POST, uri"/api/sessions")
      .withBodyStream(fs2.Stream.emits("{}".getBytes("UTF-8")).covary[IO])
    val createJson = bodyJson(run(routes, createReq))
    val sessionId  = createJson("session")("sessionId").str

    // now retrieve it
    val getResp = run(routes, Request[IO](Method.GET, Uri.unsafeFromString(s"/api/sessions/$sessionId")))
    getResp.status                         shouldBe Status.Ok
    val json = bodyJson(getResp)
    json("sessionId").str                  shouldBe sessionId
    json("lifecycle").str                  shouldBe "Created"
  }

  it should "return 404 for an unknown session id" in {
    val routes   = makeRoutes()
    val unknown  = UUID.randomUUID().toString
    val resp     = run(routes, Request[IO](Method.GET, Uri.unsafeFromString(s"/api/sessions/$unknown")))
    resp.status                  shouldBe Status.NotFound
    bodyJson(resp)("code").str   shouldBe "SESSION_NOT_FOUND"
  }

  it should "return 400 for a non-UUID session id" in {
    val routes = makeRoutes()
    val resp   = run(routes, Request[IO](Method.GET, uri"/api/sessions/not-a-uuid"))
    resp.status                  shouldBe Status.BadRequest
    bodyJson(resp)("code").str   shouldBe "BAD_REQUEST"
  }
