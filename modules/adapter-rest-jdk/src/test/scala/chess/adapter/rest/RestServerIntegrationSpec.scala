package chess.adapter.rest

import com.sun.net.httpserver.HttpServer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.util.UUID

/** End-to-end integration tests for the REST adapter.
 *
 *  Starts a real [[RestServer]] bound to an ephemeral port (0) before the
 *  suite runs and stops it after.  Tests issue real HTTP requests through
 *  `java.net.http.HttpClient` (JDK 11+) and assert on observable HTTP
 *  behaviour: status codes, JSON shapes, and error codes.
 *
 *  These tests are the primary validation mechanism for [[SessionRoutes]],
 *  [[GameRoutes]], and [[RouteSupport]], which are excluded from scoverage
 *  metrics (see build.sbt) because they cannot be instrumented without a
 *  live server.
 */
class RestServerIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private var httpServer: HttpServer = _
  private var baseUrl: String        = _
  // HttpClient is thread-safe and can be shared across all tests.
  private val client: HttpClient = HttpClient.newHttpClient()

  override def beforeAll(): Unit =
    httpServer = RestServer(0).start()
    baseUrl    = s"http://localhost:${httpServer.getAddress.getPort}"

  override def afterAll(): Unit =
    httpServer.stop(0)

  // ── HTTP helpers ───────────────────────────────────────────────────────────

  /** POST `path` with a JSON body; return (HTTP status, parsed response JSON). */
  private def post(path: String, body: String): (Int, ujson.Value) =
    val req  = HttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl$path"))
      .header("Content-Type", "application/json")
      .POST(BodyPublishers.ofString(body))
      .build()
    val resp = client.send(req, BodyHandlers.ofString())
    (resp.statusCode(), ujson.read(resp.body()))

  /** GET `path`; return (HTTP status, parsed response JSON). */
  private def get(path: String): (Int, ujson.Value) =
    val req  = HttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl$path"))
      .GET()
      .build()
    val resp = client.send(req, BodyHandlers.ofString())
    (resp.statusCode(), ujson.read(resp.body()))

  /** Create a fresh HumanVsHuman session; return (sessionId, gameId). */
  private def createSession(): (String, String) =
    val (_, json) = post("/sessions", """{}""")
    (json("session")("sessionId").str, json("session")("gameId").str)

  // ── POST /sessions ─────────────────────────────────────────────────────────

  "POST /sessions" should "return 201 with a bundled session and initial game state" in {
    val (status, json) = post("/sessions", """{}""")
    status                                 shouldBe 201
    json("session")("sessionId").str       should not be empty
    json("session")("gameId").str          should not be empty
    json("session")("lifecycle").str       shouldBe "Created"
    json("session")("mode").str            shouldBe "HumanVsHuman"
    json("game")("currentPlayer").str      shouldBe "White"
    json("game")("status").str             shouldBe "Ongoing"
    json("game")("board").arr              should have size 32
  }

  it should "accept explicit mode and controller fields" in {
    val body = """{"mode":"HumanVsHuman","whiteController":"HumanLocal","blackController":"HumanLocal"}"""
    val (status, json) = post("/sessions", body)
    status                                       shouldBe 201
    json("session")("mode").str                  shouldBe "HumanVsHuman"
    json("session")("whiteController").str       shouldBe "HumanLocal"
    json("session")("blackController").str       shouldBe "HumanLocal"
  }

  it should "yield a gameId that GET /games/{gameId} can resolve" in {
    val (_, gameId)    = createSession()
    val (status, json) = get(s"/games/$gameId")
    status                    shouldBe 200
    json("gameId").str        shouldBe gameId
    json("board").arr         should have size 32
  }

  // ── GET /sessions/{sessionId} ──────────────────────────────────────────────

  "GET /sessions/{sessionId}" should "return 200 with the created session's data" in {
    val (sessionId, gameId) = createSession()
    val (status, json)      = get(s"/sessions/$sessionId")
    status                  shouldBe 200
    json("sessionId").str   shouldBe sessionId
    json("gameId").str      shouldBe gameId
    json("lifecycle").str   shouldBe "Created"
  }

  it should "return 404 for an unknown session id" in {
    val (status, json) = get(s"/sessions/${UUID.randomUUID()}")
    status             shouldBe 404
    json("code").str   shouldBe "SESSION_NOT_FOUND"
  }

  it should "return 400 for a non-UUID session id" in {
    val (status, json) = get("/sessions/not-a-uuid")
    status             shouldBe 400
    json("code").str   shouldBe "BAD_REQUEST"
  }

  // ── GET /games/{gameId} ────────────────────────────────────────────────────

  "GET /games/{gameId}" should "return 200 with the initial game state" in {
    val (_, gameId)    = createSession()
    val (status, json) = get(s"/games/$gameId")
    status                      shouldBe 200
    json("gameId").str          shouldBe gameId
    json("currentPlayer").str   shouldBe "White"
    json("status").str          shouldBe "Ongoing"
    json("inCheck").bool        shouldBe false
    json("board").arr           should have size 32
  }

  it should "return 404 for an unknown game id" in {
    val (status, json) = get(s"/games/${UUID.randomUUID()}")
    status             shouldBe 404
    json("code").str   shouldBe "GAME_NOT_FOUND"
  }

  it should "return 400 for a non-UUID game id" in {
    val (status, json) = get("/games/not-a-uuid")
    status             shouldBe 400
    json("code").str   shouldBe "BAD_REQUEST"
  }

  // ── POST /games/{gameId}/moves ─────────────────────────────────────────────

  "POST /games/{gameId}/moves" should "apply a legal opening move and report Active lifecycle" in {
    val (_, gameId)    = createSession()
    val (status, json) = post(s"/games/$gameId/moves", """{"from":"e2","to":"e4"}""")
    status                              shouldBe 200
    json("game")("currentPlayer").str   shouldBe "Black"
    json("game")("board").arr           should have size 32
    json("sessionLifecycle").str        shouldBe "Active"
  }

  it should "persist the updated state so a subsequent GET /games/{id} reflects the move" in {
    val (_, gameId) = createSession()
    post(s"/games/$gameId/moves", """{"from":"e2","to":"e4"}""")
    val (status, json) = get(s"/games/$gameId")
    status                      shouldBe 200
    json("currentPlayer").str   shouldBe "Black"
  }

  it should "return 400 for a body with missing required fields" in {
    val (_, gameId)    = createSession()
    val (status, json) = post(s"/games/$gameId/moves", """{"invalid":"payload"}""")
    status             shouldBe 400
    json("code").str   shouldBe "BAD_REQUEST"
  }

  it should "return 400 for unparseable JSON" in {
    val (_, gameId)    = createSession()
    val (status, json) = post(s"/games/$gameId/moves", "not json at all")
    status             shouldBe 400
    json("code").str   shouldBe "BAD_REQUEST"
  }

  it should "return 422 for an illegal chess move" in {
    val (_, gameId)    = createSession()
    // A pawn cannot advance three squares.
    val (status, json) = post(s"/games/$gameId/moves", """{"from":"e2","to":"e5"}""")
    status             shouldBe 422
    json("code").str   shouldBe "ILLEGAL_MOVE"
  }

  it should "return 422 for an invalid square in the 'from' field" in {
    val (_, gameId)    = createSession()
    val (status, json) = post(s"/games/$gameId/moves", """{"from":"z9","to":"e4"}""")
    status             shouldBe 422
    json("code").str   shouldBe "INVALID_MOVE"
  }

  it should "return 422 for an invalid promotion piece type" in {
    val (_, gameId)    = createSession()
    // King is not a legal promotion target; MoveMapper rejects it before chess rules are consulted.
    val (status, json) = post(s"/games/$gameId/moves",
      """{"from":"e2","to":"e4","promotion":"King"}""")
    status             shouldBe 422
    json("code").str   shouldBe "INVALID_MOVE"
  }

  it should "return 404 for an unknown game id" in {
    val (status, json) = post(s"/games/${UUID.randomUUID()}/moves",
      """{"from":"e2","to":"e4"}""")
    status             shouldBe 404
    json("code").str   shouldBe "GAME_NOT_FOUND"
  }
