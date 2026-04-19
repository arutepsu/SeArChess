package chess.adapter.ai.remote

import chess.application.port.ai.AIError
import chess.application.port.ai.AIRequestContext
import chess.application.session.model.{GameSession, SessionMode, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.domain.model.Move
import chess.domain.state.{GameState, GameStateFactory}
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class RemoteAiProviderSpec extends AnyFlatSpec with Matchers with EitherValues:

  private def context(
    state:     GameState = GameStateFactory.initial(),
    requestId: String = "ctx-request"
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

  "RemoteAiProvider.suggestMove" should "map a successful remote response into AIResponse" in {
    var capturedBody = ""

    withServer { exchange =>
      capturedBody = requestBody(exchange)
      respond(exchange, 200, """{"requestId":"req-1","move":{"from":"e2","to":"e4"},"engineId":"stockfish"}""")
    } { baseUrl =>
      val provider = RemoteAiProvider(baseUrl, timeoutMillis = 1000, defaultEngineId = Some("stockfish"))

      val aiContext = context(requestId = "req-1")
      val response = provider.suggestMove(aiContext).value

      response.move shouldBe Move(
        chess.domain.model.Position.fromAlgebraic("e2").value,
        chess.domain.model.Position.fromAlgebraic("e4").value
      )
      val json = ujson.read(capturedBody)
      json("requestId").str                  shouldBe "req-1"
      json("gameId").str                     shouldBe aiContext.gameId.value.toString
      json("sessionId").str                  shouldBe aiContext.sessionId.value.toString
      json("fen").str                        should include (" w ")
      json("legalMoves").arr                 should not be empty
      json("engine")("engineId").str         shouldBe "stockfish"
      json("limits")("timeoutMillis").num.toInt shouldBe 1000
    }
  }

  it should "map NO_LEGAL_MOVE remote errors to AIError.NoLegalMove" in {
    withServer { exchange =>
      requestBody(exchange)
      respond(exchange, 422, """{"requestId":"req-2","code":"NO_LEGAL_MOVE","message":"no legal moves"}""")
    } { baseUrl =>
      val provider = RemoteAiProvider(baseUrl, timeoutMillis = 1000)

      provider.suggestMove(context(requestId = "req-2")).left.value shouldBe AIError.NoLegalMove
    }
  }

  it should "map engine unavailable remote errors to EngineFailure" in {
    withServer { exchange =>
      requestBody(exchange)
      respond(exchange, 503, """{"requestId":"req-3","code":"ENGINE_UNAVAILABLE","message":"engine missing"}""")
    } { baseUrl =>
      val provider = RemoteAiProvider(baseUrl, timeoutMillis = 1000)

      provider.suggestMove(context(requestId = "req-3")).left.value shouldBe
        AIError.EngineFailure("ENGINE_UNAVAILABLE: engine missing")
    }
  }

  it should "map malformed success responses to EngineFailure" in {
    withServer { exchange =>
      requestBody(exchange)
      respond(exchange, 200, """{"requestId":"req-4","move":{"from":"e2"}}""")
    } { baseUrl =>
      val provider = RemoteAiProvider(baseUrl, timeoutMillis = 1000)

      provider.suggestMove(context(requestId = "req-4")).left.value shouldBe
        AIError.EngineFailure("Missing or invalid 'to' in AI response")
    }
  }

  it should "map HTTP client timeout to EngineFailure" in {
    withServer { exchange =>
      requestBody(exchange)
      Thread.sleep(300)
      respond(exchange, 200, """{"requestId":"req-5","move":{"from":"e2","to":"e4"}}""")
    } { baseUrl =>
      val provider = RemoteAiProvider(baseUrl, timeoutMillis = 50)

      provider.suggestMove(context(requestId = "req-5")).left.value shouldBe
        AIError.EngineFailure("timeout")
    }
  }
