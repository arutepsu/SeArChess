package chess.adapter.ai.remote

import chess.application.port.ai.AIRequestContext
import chess.application.session.model.{GameSession, SessionMode, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.domain.model.Color
import chess.domain.state.{GameState, GameStateFactory}
import chess.notation.api.{ImportResult, ImportTarget}
import chess.notation.fen.{FenImporter, FenParser}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RemoteAiRequestMapperSpec extends AnyFlatSpec with Matchers with EitherValues:

  private def stateFromFen(fen: String): GameState =
    val parsed = FenParser.parse(fen).fold(e => throw RuntimeException(s"FEN parse: $e"), identity)
    FenImporter
      .importNotation(parsed, ImportTarget.PositionTarget)
      .fold(e => throw RuntimeException(s"FEN import: $e"), identity) match
      case r: ImportResult.PositionImportResult[GameState @unchecked] => r.data
      case other => throw RuntimeException(s"unexpected import result: $other")

  private def aiSession(engineId: Option[String] = Some("stockfish-default")) =
    GameSession.create(
      gameId = GameId.random(),
      mode = SessionMode.HumanVsAI,
      whiteController = SideController.AI(engineId),
      blackController = SideController.HumanLocal
    )

  "RemoteAiRequestMapper.toRequest" should "map session and game state to the remote AI request DTO" in {
    val state = GameStateFactory.initial()
    val session = aiSession()

    val request = RemoteAiRequestMapper
      .toRequest(
        context = AIRequestContext.fromSession(session, state, requestId = "req-1"),
        timeoutMillis = 1500,
        defaultEngineId = None
      )
      .value

    request.requestId shouldBe "req-1"
    request.gameId shouldBe session.gameId.value.toString
    request.sessionId shouldBe session.sessionId.value.toString
    request.fen shouldBe "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    request.engine.engineId shouldBe Some("stockfish-default")
    request.limits.timeoutMillis shouldBe 1500
    request.metadata.mode shouldBe "HumanVsAI"
    request.legalMoves should have size 20
    request.legalMoves.map(m => (m.from, m.to)) should contain allOf (
      ("e2", "e3"),
      ("e2", "e4"),
      ("g1", "f3")
    )
  }

  it should "keep local-dev test mode out of the request body contract" in {
    val request = RemoteAiRequestMapper
      .toRequest(
        context = AIRequestContext
          .fromSession(aiSession(), GameStateFactory.initial(), requestId = "req-test"),
        timeoutMillis = 1000,
        defaultEngineId = None
      )
      .value

    request.metadata.mode shouldBe "HumanVsAI"
    RemoteAiJson.requestToJson(request) should not include "testMode"
  }

  it should "send sideToMove as lowercase 'white' for a white-to-move position" in {
    val request = RemoteAiRequestMapper
      .toRequest(
        context = AIRequestContext
          .fromSession(aiSession(), GameStateFactory.initial(), requestId = "req-w"),
        timeoutMillis = 1000,
        defaultEngineId = None
      )
      .value

    request.sideToMove shouldBe "white"
  }

  it should "send sideToMove as lowercase 'black' for a black-to-move position" in {
    val blackToMove = GameStateFactory.initial().copy(currentPlayer = Color.Black)
    val session = GameSession.create(
      gameId = GameId.random(),
      mode = SessionMode.HumanVsAI,
      whiteController = SideController.HumanLocal,
      blackController = SideController.AI(Some("stockfish"))
    )

    val request = RemoteAiRequestMapper
      .toRequest(
        context = AIRequestContext.fromSession(session, blackToMove, requestId = "req-b"),
        timeoutMillis = 1000,
        defaultEngineId = None
      )
      .value

    request.sideToMove shouldBe "black"
  }

  it should "send promotion piece values as lowercase in legalMoves" in {
    // Pawn on a7 ready to promote; four promotion choices expected.
    val promoState = stateFromFen("8/P7/8/8/8/8/8/K6k w - - 0 1")
    val session = aiSession()

    val request = RemoteAiRequestMapper
      .toRequest(
        context = AIRequestContext.fromSession(session, promoState, requestId = "req-promo"),
        timeoutMillis = 1000,
        defaultEngineId = None
      )
      .value

    val promoMoves = request.legalMoves.flatMap(_.promotion)
    promoMoves should not be empty
    promoMoves should contain allOf ("queen", "rook", "bishop", "knight")
    promoMoves.foreach(p => p shouldBe p.toLowerCase)
  }

  it should "use the default engine id when the AI controller has no engine id" in {
    val session = GameSession.create(
      gameId = GameId.random(),
      mode = SessionMode.HumanVsAI,
      whiteController = SideController.AI(),
      blackController = SideController.HumanLocal
    )

    val request = RemoteAiRequestMapper
      .toRequest(
        requestId = "req-2",
        session = session,
        state = GameStateFactory.initial(),
        timeoutMillis = 2000,
        defaultEngineId = Some("default-engine")
      )
      .value

    request.engine.engineId shouldBe Some("default-engine")
  }

  it should "leave engine id empty when neither controller nor config selects one" in {
    val session = GameSession.create(
      gameId = GameId.random(),
      mode = SessionMode.HumanVsAI,
      whiteController = SideController.AI(),
      blackController = SideController.HumanLocal
    )

    val request = RemoteAiRequestMapper
      .toRequest(
        requestId = "req-3",
        session = session,
        state = GameStateFactory.initial(),
        timeoutMillis = 2000
      )
      .value

    request.engine.engineId shouldBe None
  }
