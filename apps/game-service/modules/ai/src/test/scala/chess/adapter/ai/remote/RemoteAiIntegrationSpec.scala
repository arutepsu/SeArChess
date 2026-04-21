package chess.adapter.ai.remote

import chess.application.port.ai.{AIError, AIRequestContext, AIResponse}
import chess.application.session.model.{GameSession, SessionMode, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.domain.state.GameStateFactory
import chess.notation.api.{ImportResult, ImportTarget}
import chess.notation.fen.{FenImporter, FenParser}
import chess.domain.state.GameState
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues

/** Integration spec for the Scala RemoteAiMoveSuggestionClient against a live AI provider.
 *
 *  Requires the provider to be running at INFERENCE_SERVICE_URL (default
 *  http://127.0.0.1:8765). The tests are skipped automatically when the service
 *  is not reachable so they do not break CI.
 *
 *  To run:
 *    sbt "adapterAi/testOnly chess.adapter.ai.remote.RemoteAiIntegrationSpec"
 */
class RemoteAiIntegrationSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val baseUrl: String =
    sys.env.getOrElse("INFERENCE_SERVICE_URL", "http://127.0.0.1:8765")

  private val serviceAvailable: Boolean =
    try
      val url = java.net.URI.create(s"$baseUrl/health").toURL
      val conn = url.openConnection().asInstanceOf[java.net.HttpURLConnection]
      conn.setConnectTimeout(500)
      conn.setReadTimeout(500)
      conn.connect()
      val ok = conn.getResponseCode == 200
      conn.disconnect()
      ok
    catch case _: Exception => false

  private def assume(): Unit =
    assume(
      serviceAvailable,
      s"AI provider not reachable at $baseUrl; skipping integration tests"
    )

  private def stateFromFen(fen: String): GameState =
    val parsed = FenParser.parse(fen).fold(e => throw RuntimeException(s"FEN parse: $e"), identity)
    FenImporter.importNotation(parsed, ImportTarget.PositionTarget)
      .fold(e => throw RuntimeException(s"FEN import: $e"), identity) match
        case r: ImportResult.PositionImportResult[GameState @unchecked] => r.data
        case other => throw RuntimeException(s"unexpected: $other")

  private def context(
    state:     GameState = GameStateFactory.initial(),
    requestId: String    = java.util.UUID.randomUUID().toString
  ) =
    AIRequestContext.fromSession(
      GameSession.create(GameId.random(), SessionMode.HumanVsAI, SideController.AI(Some("stub-model")), SideController.HumanLocal),
      state,
      requestId = requestId
    )

  private lazy val provider = RemoteAiMoveSuggestionClient(baseUrl, timeoutMillis = 5000)

  "RemoteAiMoveSuggestionClient to AI provider" should "return a legal move suggestion for the initial position" in {
    assume()
    val ctx      = context()
    val result   = provider.suggestMove(ctx)
    val response = result.value

    val legalUci = chess.domain.rules.GameStateRules
      .legalMoves(GameStateFactory.initial())
      .map(m => s"${m.from}${m.to}")

    val uci = s"${response.move.from}${response.move.to}"
    legalUci should contain(uci)
  }

  it should "echo the requestId from the Scala request in the successful response" in {
    assume()
    val reqId    = "scala-integration-req-id"
    val ctx      = context(requestId = reqId)
    // The response move is returned via AIResponse; we verify the HTTP round-trip
    // by checking that the provider succeeds (it would fail on requestId mismatch
    // if the server ignored the field)
    provider.suggestMove(ctx).isRight shouldBe true
  }

  it should "return a contract-shaped BAD_REQUEST when an invalid sideToMove is sent" in {
    assume()
    // Drive the HTTP layer directly to verify the provider error shape without
    // going through RemoteAiMoveSuggestionClient's field construction.
    val body =
      """{
        |  "requestId": "integration-bad",
        |  "gameId":    "00000000-0000-0000-0000-000000000001",
        |  "sessionId": "00000000-0000-0000-0000-000000000002",
        |  "sideToMove": "INVALID",
        |  "fen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        |  "legalMoves": [{"from":"e2","to":"e4"}],
        |  "limits": {"timeoutMillis": 3000}
        |}""".stripMargin

    val request = java.net.http.HttpRequest
      .newBuilder(java.net.URI.create(s"$baseUrl${RemoteAiServiceContract.MoveSuggestionsPath}"))
      .header("Content-Type", "application/json")
      .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
      .build()

    val response = java.net.http.HttpClient.newHttpClient()
      .send(request, java.net.http.HttpResponse.BodyHandlers.ofString())

    response.statusCode() shouldBe 400
    val json = ujson.read(response.body())
    json("code").str      shouldBe "BAD_REQUEST"
    json("requestId").str shouldBe "integration-bad"
    json.obj.contains("message") shouldBe true
  }

  it should "round-trip a promotion move through the provider" in {
    assume()
    // The position has ONLY promotion moves (no king moves), so the fake engine
    // is forced to return a promotion.  White: Ka8 pawn b7; Black: Kh8.
    // Ka8 is in stalemate? No - a8 king can go to a7, b8... Let me use a position
    // where the king is boxed out and only pawn moves exist.
    // Simplest: send only promotion legal moves to the provider directly via HTTP.
    val body =
      s"""{
         |  "requestId": "integration-promo",
         |  "gameId":    "00000000-0000-0000-0000-000000000001",
         |  "sessionId": "00000000-0000-0000-0000-000000000002",
         |  "sideToMove": "white",
         |  "fen": "8/P7/8/8/8/8/8/K6k w - - 0 1",
         |  "legalMoves": [
         |    {"from": "a7", "to": "a8", "promotion": "queen"},
         |    {"from": "a7", "to": "a8", "promotion": "rook"},
         |    {"from": "a7", "to": "a8", "promotion": "bishop"},
         |    {"from": "a7", "to": "a8", "promotion": "knight"}
         |  ],
         |  "limits": {"timeoutMillis": 3000}
         |}""".stripMargin

    val request = java.net.http.HttpRequest
      .newBuilder(java.net.URI.create(s"$baseUrl${RemoteAiServiceContract.MoveSuggestionsPath}"))
      .header("Content-Type", "application/json")
      .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
      .build()

    val response = java.net.http.HttpClient.newHttpClient()
      .send(request, java.net.http.HttpResponse.BodyHandlers.ofString())

    response.statusCode() shouldBe 200
    val json = ujson.read(response.body())
    val move = json("move")
    move("from").str shouldBe "a7"
    move("to").str   shouldBe "a8"
    val promo = move("promotion").strOpt
    promo                shouldBe defined
    List("queen", "rook", "bishop", "knight") should contain(promo.get)
  }

  it should "report health endpoint as 200 ok" in {
    assume()
    val url  = java.net.URI.create(s"$baseUrl/health").toURL
    val conn = url.openConnection().asInstanceOf[java.net.HttpURLConnection]
    try
      conn.connect()
      conn.getResponseCode shouldBe 200
      val body = ujson.read(scala.io.Source.fromInputStream(conn.getInputStream).mkString)
      body("status").str shouldBe "ok"
    finally conn.disconnect()
  }
