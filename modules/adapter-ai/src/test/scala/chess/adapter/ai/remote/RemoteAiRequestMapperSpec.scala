package chess.adapter.ai.remote

import chess.application.port.ai.AIRequestContext
import chess.application.session.model.{GameSession, SessionMode, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.domain.model.Color
import chess.domain.state.GameStateFactory
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RemoteAiRequestMapperSpec extends AnyFlatSpec with Matchers with EitherValues:

  "RemoteAiRequestMapper.toRequest" should "map session and game state to the remote AI request DTO" in {
    val state = GameStateFactory.initial()
    val session = GameSession.create(
      gameId          = GameId.random(),
      mode            = SessionMode.HumanVsAI,
      whiteController = SideController.AI(Some("stockfish-default")),
      blackController = SideController.HumanLocal
    )

    val request = RemoteAiRequestMapper
      .toRequest(
        context       = AIRequestContext.fromSession(session, state, requestId = "req-1"),
        timeoutMillis = 1500,
        defaultEngineId = None
      )
      .value

    request.requestId       shouldBe "req-1"
    request.gameId          shouldBe session.gameId.value.toString
    request.sessionId       shouldBe session.sessionId.value.toString
    request.sideToMove      shouldBe Color.White.toString
    request.fen             shouldBe "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    request.engine.engineId shouldBe Some("stockfish-default")
    request.limits.timeoutMillis shouldBe 1500
    request.metadata.mode        shouldBe "HumanVsAI"
    request.legalMoves           should have size 20
    request.legalMoves.map(m => (m.from, m.to)) should contain allOf (("e2", "e3"), ("e2", "e4"), ("g1", "f3"))
  }

  it should "use the default engine id when the AI controller has no engine id" in {
    val session = GameSession.create(
      gameId          = GameId.random(),
      mode            = SessionMode.HumanVsAI,
      whiteController = SideController.AI(),
      blackController = SideController.HumanLocal
    )

    val request = RemoteAiRequestMapper
      .toRequest(
        requestId       = "req-2",
        session         = session,
        state           = GameStateFactory.initial(),
        timeoutMillis   = 2000,
        defaultEngineId = Some("default-engine")
      )
      .value

    request.engine.engineId shouldBe Some("default-engine")
  }

  it should "leave engine id empty when neither controller nor config selects one" in {
    val session = GameSession.create(
      gameId          = GameId.random(),
      mode            = SessionMode.HumanVsAI,
      whiteController = SideController.AI(),
      blackController = SideController.HumanLocal
    )

    val request = RemoteAiRequestMapper
      .toRequest(
        requestId     = "req-3",
        session       = session,
        state         = GameStateFactory.initial(),
        timeoutMillis = 2000
      )
      .value

    request.engine.engineId shouldBe None
  }
