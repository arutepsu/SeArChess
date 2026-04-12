package chess.adapter.http4s

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.adapter.http4s.route.{Http4sGameRoutes, Http4sSessionRoutes}
import chess.adapter.repository.{InMemoryGameRepository, InMemorySessionRepository}
import chess.application.session.service.SessionService
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

  /** Returns (gameRoutes, sessionRoutes) sharing the same repositories. */
  private def makeRoutes() =
    val sessionRepo = InMemorySessionRepository()
    val gameRepo    = InMemoryGameRepository()
    val service     = SessionService(sessionRepo, _ => ())
    val gameRoutes  = Http4sGameRoutes(service, gameRepo).routes.orNotFound
    val sessRoutes  = Http4sSessionRoutes(service, gameRepo).routes.orNotFound
    (gameRoutes, sessRoutes)

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
