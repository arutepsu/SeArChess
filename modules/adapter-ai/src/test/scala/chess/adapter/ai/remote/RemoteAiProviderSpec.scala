package chess.adapter.ai.remote

import chess.application.port.ai.AIError
import chess.application.port.ai.AIRequestContext
import chess.application.session.model.{GameSession, SessionMode, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.domain.model.Move
import chess.domain.state.{GameState, GameStateFactory}
import chess.notation.api.{ImportResult, ImportTarget}
import chess.notation.fen.{FenImporter, FenParser}
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class RemoteAiProviderSpec extends AnyFlatSpec with Matchers with EitherValues:

  private def stateFromFen(fen: String): GameState =
    val parsed = FenParser.parse(fen).fold(e => throw RuntimeException(s"FEN parse: $e"), identity)
    FenImporter.importNotation(parsed, ImportTarget.PositionTarget)
      .fold(e => throw RuntimeException(s"FEN import: $e"), identity) match
        case r: ImportResult.PositionImportResult[GameState @unchecked] => r.data
        case other => throw RuntimeException(s"unexpected: $other")

  private def context(
    state:     GameState = GameStateFactory.initial(),
    requestId: String    = "ctx-request"
  ): AIRequestContext =
    AIRequestContext.fromSession(
      GameSession.create(GameId.random(), SessionMode.HumanVsAI, SideController.AI(Some("stockfish")), SideController.HumanLocal),
      state,
      requestId = requestId
    )

  private def withServer(handler: HttpExchange => Unit)(test: String => Unit): Unit =
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/v1/move-suggestions", exchange => handler(exchange))
    server.start()
    try test(s"http://127.0.0.1:${server.getAddress.getPort}")
    finally server.stop(0)

  private def respond(exchange: HttpExchange, status: Int, body: String): Unit =
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    val os = exchange.getResponseBody
    try os.write(bytes)
    finally os.close()

  private def requestBody(exchange: HttpExchange): String =
    String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)

  // ---------------------------------------------------------------------------
  // Happy path
  // ---------------------------------------------------------------------------

  "RemoteAiProvider.suggestMove" should "map a successful remote response into AIResponse" in {
    var capturedBody = ""

    withServer { exchange =>
      capturedBody = requestBody(exchange)
      respond(exchange, 200, """{"requestId":"req-1","move":{"from":"e2","to":"e4"},"engineId":"stockfish"}""")
    } { baseUrl =>
      val provider   = RemoteAiProvider(baseUrl, timeoutMillis = 1000, defaultEngineId = Some("stockfish"))
      val aiContext  = context(requestId = "req-1")
      val response   = provider.suggestMove(aiContext).value

      response.move shouldBe Move(
        chess.domain.model.Position.fromAlgebraic("e2").value,
        chess.domain.model.Position.fromAlgebraic("e4").value
      )

      val json = ujson.read(capturedBody)
      json("requestId").str                     shouldBe "req-1"
      json("gameId").str                        shouldBe aiContext.gameId.value.toString
      json("sessionId").str                     shouldBe aiContext.sessionId.value.toString
      json("fen").str                           should include (" w ")
      json("sideToMove").str                    shouldBe "white"
      json("legalMoves").arr                    should not be empty
      json("engine")("engineId").str            shouldBe "stockfish"
      json("limits")("timeoutMillis").num.toInt shouldBe 1000
    }
  }

  it should "parse engineVersion, elapsedMillis, and confidence from a successful response" in {
    val json = RemoteAiJson
      .responseFromJson("""{"requestId":"r","move":{"from":"e2","to":"e4"},"engineId":"e","engineVersion":"1.2.3","elapsedMillis":42,"confidence":0.87}""")
      .value

    json.engineVersion shouldBe Some("1.2.3")
    json.elapsedMillis shouldBe Some(42)
    json.confidence    shouldBe Some(0.87)
    json.engineId      shouldBe Some("e")
  }

  it should "handle a response that omits optional metadata fields" in {
    val json = RemoteAiJson
      .responseFromJson("""{"requestId":"r","move":{"from":"e2","to":"e4"}}""")
      .value

    json.engineId      shouldBe None
    json.engineVersion shouldBe None
    json.elapsedMillis shouldBe None
    json.confidence    shouldBe None
  }

  // ---------------------------------------------------------------------------
  // Request wire format
  // ---------------------------------------------------------------------------

  it should "send sideToMove as lowercase 'white' in the JSON request body" in {
    var capturedBody = ""
    withServer { exchange =>
      capturedBody = requestBody(exchange)
      respond(exchange, 200, """{"requestId":"req-case","move":{"from":"e2","to":"e4"}}""")
    } { baseUrl =>
      RemoteAiProvider(baseUrl, timeoutMillis = 1000)
        .suggestMove(context(requestId = "req-case"))
      ujson.read(capturedBody)("sideToMove").str shouldBe "white"
    }
  }

  it should "send promotion values as lowercase in legalMoves" in {
    val promoState   = stateFromFen("8/P7/8/8/8/8/8/K6k w - - 0 1")
    var capturedBody = ""

    withServer { exchange =>
      capturedBody = requestBody(exchange)
      respond(exchange, 200, """{"requestId":"req-promo","move":{"from":"a7","to":"a8","promotion":"queen"}}""")
    } { baseUrl =>
      RemoteAiProvider(baseUrl, timeoutMillis = 1000)
        .suggestMove(context(state = promoState, requestId = "req-promo"))

      val moves = ujson.read(capturedBody)("legalMoves").arr
      val promoValues = moves.flatMap(_.obj.get("promotion").map(_.str))
      promoValues should not be empty
      promoValues should contain allOf ("queen", "rook", "bishop", "knight")
      promoValues.foreach(p => p shouldBe p.toLowerCase)
    }
  }

  // ---------------------------------------------------------------------------
  // Error code mapping — all six contract codes
  // ---------------------------------------------------------------------------

  it should "map NO_LEGAL_MOVE (422) to AIError.NoLegalMove" in {
    withServer { exchange =>
      requestBody(exchange)
      respond(exchange, 422, """{"requestId":"req-err","code":"NO_LEGAL_MOVE","message":"no legal moves"}""")
    } { baseUrl =>
      val provider = RemoteAiProvider(baseUrl, timeoutMillis = 1000)
      provider.suggestMove(context(requestId = "req-err")).left.value shouldBe AIError.NoLegalMove
    }
  }

  it should "map BAD_REQUEST (400) to AIError.EngineFailure" in {
    withServer { exchange =>
      requestBody(exchange)
      respond(exchange, 400, """{"requestId":"req-br","code":"BAD_REQUEST","message":"missing field fen"}""")
    } { baseUrl =>
      val provider = RemoteAiProvider(baseUrl, timeoutMillis = 1000)
      provider.suggestMove(context(requestId = "req-br")).left.value shouldBe
        AIError.EngineFailure("BAD_REQUEST: missing field fen")
    }
  }

  it should "map BAD_POSITION (422) to AIError.EngineFailure" in {
    withServer { exchange =>
      requestBody(exchange)
      respond(exchange, 422, """{"requestId":"req-bp","code":"BAD_POSITION","message":"invalid FEN"}""")
    } { baseUrl =>
      val provider = RemoteAiProvider(baseUrl, timeoutMillis = 1000)
      provider.suggestMove(context(requestId = "req-bp")).left.value shouldBe
        AIError.EngineFailure("BAD_POSITION: invalid FEN")
    }
  }

  it should "map ENGINE_UNAVAILABLE (503) to AIError.EngineFailure" in {
    withServer { exchange =>
      requestBody(exchange)
      respond(exchange, 503, """{"requestId":"req-eu","code":"ENGINE_UNAVAILABLE","message":"engine missing"}""")
    } { baseUrl =>
      val provider = RemoteAiProvider(baseUrl, timeoutMillis = 1000)
      provider.suggestMove(context(requestId = "req-eu")).left.value shouldBe
        AIError.EngineFailure("ENGINE_UNAVAILABLE: engine missing")
    }
  }

  it should "map ENGINE_TIMEOUT (504) to AIError.EngineFailure" in {
    withServer { exchange =>
      requestBody(exchange)
      respond(exchange, 504, """{"requestId":"req-et","code":"ENGINE_TIMEOUT","message":"timed out after 3000ms"}""")
    } { baseUrl =>
      val provider = RemoteAiProvider(baseUrl, timeoutMillis = 1000)
      provider.suggestMove(context(requestId = "req-et")).left.value shouldBe
        AIError.EngineFailure("ENGINE_TIMEOUT: timed out after 3000ms")
    }
  }

  it should "map ENGINE_FAILURE (500) to AIError.EngineFailure" in {
    withServer { exchange =>
      requestBody(exchange)
      respond(exchange, 500, """{"requestId":"req-ef","code":"ENGINE_FAILURE","message":"internal error"}""")
    } { baseUrl =>
      val provider = RemoteAiProvider(baseUrl, timeoutMillis = 1000)
      provider.suggestMove(context(requestId = "req-ef")).left.value shouldBe
        AIError.EngineFailure("ENGINE_FAILURE: internal error")
    }
  }

  it should "map an error response with missing requestId to EngineFailure preserving the code" in {
    withServer { exchange =>
      requestBody(exchange)
      respond(exchange, 500, """{"code":"ENGINE_FAILURE","message":"no request id"}""")
    } { baseUrl =>
      val provider = RemoteAiProvider(baseUrl, timeoutMillis = 1000)
      provider.suggestMove(context(requestId = "req-noid")).left.value shouldBe
        AIError.EngineFailure("ENGINE_FAILURE: no request id")
    }
  }

  // ---------------------------------------------------------------------------
  // Transport failures
  // ---------------------------------------------------------------------------

  it should "map malformed success responses to EngineFailure" in {
    withServer { exchange =>
      requestBody(exchange)
      respond(exchange, 200, """{"requestId":"req-mal","move":{"from":"e2"}}""")
    } { baseUrl =>
      val provider = RemoteAiProvider(baseUrl, timeoutMillis = 1000)
      provider.suggestMove(context(requestId = "req-mal")).left.value shouldBe
        AIError.EngineFailure("Missing or invalid 'to' in AI response")
    }
  }

  it should "map HTTP client timeout to EngineFailure" in {
    withServer { exchange =>
      requestBody(exchange)
      Thread.sleep(300)
      respond(exchange, 200, """{"requestId":"req-to","move":{"from":"e2","to":"e4"}}""")
    } { baseUrl =>
      val provider = RemoteAiProvider(baseUrl, timeoutMillis = 50)
      provider.suggestMove(context(requestId = "req-to")).left.value shouldBe
        AIError.EngineFailure("timeout")
    }
  }
