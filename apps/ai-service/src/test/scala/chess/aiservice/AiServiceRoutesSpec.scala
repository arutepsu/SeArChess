package chess.aiservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.adapter.ai.remote.{
  RemoteAiEngineSelection,
  RemoteAiJson,
  RemoteAiLimits,
  RemoteAiMetadata,
  RemoteAiMoveDto,
  RemoteAiMoveSuggestionRequest
}
import org.http4s.*
import org.http4s.implicits.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AiServiceRoutesSpec extends AnyFlatSpec with Matchers:

  // White Ke6 Qc7 vs Black Ke8 — small tree, fast at depth 4.
  private val testFen = "4k3/2Q5/4K3/8/8/8/8/8 w - - 0 1"

  private val defaultLegalMoves = List(
    RemoteAiMoveDto("c7", "c8"), // Qc8# (checkmate)
    RemoteAiMoveDto("c7", "f7"), // Qf7
    RemoteAiMoveDto("c7", "d7")  // Qd7+
  )

  private val baseConfig = AiServiceConfig(
    host                  = "localhost",
    port                  = 8765,
    engineId              = "test-engine",
    pythonAiBaseUrl       = None,
    pythonAiTimeoutMillis = 200
  )

  private def makeApp(config: AiServiceConfig = baseConfig): HttpApp[IO] =
    AiServiceRoutes(config).routes.orNotFound

  private def post(body: String, config: AiServiceConfig = baseConfig): Response[IO] =
    makeApp(config)
      .run(
        Request[IO](Method.POST, uri"/v1/move-suggestions")
          .withBodyStream(fs2.Stream.emits(body.getBytes("UTF-8")).covary[IO])
      )
      .unsafeRunSync()

  private def bodyJson(resp: Response[IO]): ujson.Value =
    ujson.read(resp.bodyText.compile.string.unsafeRunSync())

  private def validRequest(
      legalMoves: List[RemoteAiMoveDto] = defaultLegalMoves
  ): RemoteAiMoveSuggestionRequest =
    RemoteAiMoveSuggestionRequest(
      requestId  = "req-1",
      gameId     = "game-1",
      sessionId  = "session-1",
      sideToMove = "White",
      fen        = testFen,
      legalMoves = legalMoves,
      engine     = RemoteAiEngineSelection(None),
      limits     = RemoteAiLimits(1000),
      metadata   = RemoteAiMetadata("test")
    )

  "POST /v1/move-suggestions" should "return 400 for malformed JSON" in {
    val resp = post("not-json")
    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "BAD_REQUEST"
  }

  it should "return 400 for empty legalMoves" in {
    val body = RemoteAiJson.requestToJson(validRequest(legalMoves = Nil))
    val resp = post(body)
    resp.status shouldBe Status.BadRequest
    val json = bodyJson(resp)
    json("code").str shouldBe "BAD_REQUEST"
    json("message").str should include("legalMoves")
  }

  it should "return 200 with a selected move when no Python URL is configured" in {
    val body = RemoteAiJson.requestToJson(validRequest())
    val resp = post(body)
    resp.status shouldBe Status.Ok
    val json = bodyJson(resp)
    json("requestId").str shouldBe "req-1"
    json("move")("from").str should not be empty
    json("move")("to").str should not be empty
  }

  it should "return the move in the provided legalMoves list" in {
    val body = RemoteAiJson.requestToJson(validRequest())
    val resp = post(body)
    resp.status shouldBe Status.Ok
    val json     = bodyJson(resp)
    val from     = json("move")("from").str
    val to       = json("move")("to").str
    val returned = RemoteAiMoveDto(from, to)
    defaultLegalMoves.map(m => RemoteAiMoveDto(m.from, m.to)) should contain(returned)
  }

  it should "fallback to local selection when the Python AI URL is unavailable" in {
    // Port 64999 is unlikely to be in use; the TCP connection is immediately refused,
    // triggering handleErrorWith and the local fallback.
    val config = baseConfig.copy(pythonAiBaseUrl = Some("http://localhost:64999"))
    val body   = RemoteAiJson.requestToJson(validRequest())
    val resp   = post(body, config)
    resp.status shouldBe Status.Ok
    val json = bodyJson(resp)
    json("requestId").str shouldBe "req-1"
    json("move")("from").str should not be empty
    json("move")("to").str should not be empty
  }
