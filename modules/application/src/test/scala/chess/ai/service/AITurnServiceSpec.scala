package chess.application.ai.service

import chess.adapter.ai.FirstLegalMoveProvider
import chess.adapter.repository.InMemorySessionRepository
import chess.application.ChessService
import chess.application.port.ai.{AIError, AIProvider, AIResponse}
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.application.session.service.{SessionMoveError, SessionService}
import chess.domain.model.{Move, Position}
import chess.domain.state.GameStateFactory
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AITurnServiceSpec extends AnyFlatSpec with Matchers with EitherValues:

  // Positions used to construct an illegal move (pawn at e2 → e5: three-square jump)
  private val e2 = Position.from(4, 1).value
  private val e5 = Position.from(4, 4).value

  private def freshSetup(
    whiteController: SideController = SideController.AI(),
    blackController: SideController = SideController.HumanLocal
  ) =
    val repo           = InMemorySessionRepository()
    val sessionService = SessionService(repo, _ => ())
    val gameId         = GameId.random()
    val session        = sessionService.createSession(
      gameId          = gameId,
      mode            = SessionMode.HumanVsAI,
      whiteController = whiteController,
      blackController = blackController
    ).value
    val state      = GameStateFactory.initial()
    val aiService  = AITurnService(FirstLegalMoveProvider(), sessionService, _ => ())
    (aiService, session, state, sessionService)

  // ── happy path ─────────────────────────────────────────────────────────────

  "AITurnService.requestAIMove" should "return an updated GameState after a legal AI move" in {
    val (aiService, session, state, _) = freshSetup()
    val (nextState, _) = aiService.requestAIMove(session, state).value
    nextState.moveHistory.size shouldBe 1
  }

  it should "transition the session lifecycle from Created to Active after the first AI move" in {
    val (aiService, session, state, _) = freshSetup()
    val (_, updatedSession) = aiService.requestAIMove(session, state).value
    updatedSession.lifecycle shouldBe SessionLifecycle.Active
  }

  it should "switch the current player to Black after White AI moves" in {
    val (aiService, session, state, _) = freshSetup()
    val (nextState, _) = aiService.requestAIMove(session, state).value
    nextState.currentPlayer shouldBe chess.domain.model.Color.Black
  }

  // ── not AI turn ────────────────────────────────────────────────────────────

  it should "return NotAITurn when the side to move is human-controlled" in {
    // White is HumanLocal, it is White's turn → AI service must refuse
    val (aiService, session, state, _) =
      freshSetup(whiteController = SideController.HumanLocal, blackController = SideController.AI())
    aiService.requestAIMove(session, state).left.value shouldBe AITurnError.NotAITurn
  }

  // ── provider failure ───────────────────────────────────────────────────────

  it should "return ProviderFailure(NoLegalMove) when the provider signals no legal moves" in {
    val noMoveProvider = new AIProvider:
      def suggestMove(state: chess.domain.state.GameState) = Left(AIError.NoLegalMove)
    val repo           = InMemorySessionRepository()
    val sessionService = SessionService(repo, _ => ())
    val session        = sessionService.createSession(
      gameId          = GameId.random(),
      mode            = SessionMode.HumanVsAI,
      whiteController = SideController.AI(),
      blackController = SideController.HumanLocal
    ).value
    val state     = GameStateFactory.initial()
    val aiService = AITurnService(noMoveProvider, sessionService, _ => ())
    aiService.requestAIMove(session, state).left.value shouldBe
      AITurnError.ProviderFailure(AIError.NoLegalMove)
  }

  it should "return ProviderFailure(EngineFailure) when the provider signals an engine error" in {
    val crashProvider = new AIProvider:
      def suggestMove(state: chess.domain.state.GameState) =
        Left(AIError.EngineFailure("timeout"))
    val repo           = InMemorySessionRepository()
    val sessionService = SessionService(repo, _ => ())
    val session        = sessionService.createSession(
      gameId          = GameId.random(),
      mode            = SessionMode.HumanVsAI,
      whiteController = SideController.AI(),
      blackController = SideController.HumanLocal
    ).value
    val state     = GameStateFactory.initial()
    val aiService = AITurnService(crashProvider, sessionService, _ => ())
    aiService.requestAIMove(session, state).left.value shouldBe
      AITurnError.ProviderFailure(AIError.EngineFailure("timeout"))
  }

  // ── move path not bypassed ─────────────────────────────────────────────────

  it should "return MoveFailed when the provider suggests an illegal move" in {
    // A pawn cannot jump from e2 to e5 — this verifies that the AI move goes
    // through the normal domain validation path and is not applied blindly.
    val illegalProvider = new AIProvider:
      def suggestMove(state: chess.domain.state.GameState) =
        Right(AIResponse(Move(e2, e5)))
    val repo           = InMemorySessionRepository()
    val sessionService = SessionService(repo, _ => ())
    val session        = sessionService.createSession(
      gameId          = GameId.random(),
      mode            = SessionMode.HumanVsAI,
      whiteController = SideController.AI(),
      blackController = SideController.HumanLocal
    ).value
    val state     = GameStateFactory.initial()
    val aiService = AITurnService(illegalProvider, sessionService, _ => ())
    val err = aiService.requestAIMove(session, state).left.value
    err shouldBe a[AITurnError.MoveFailed]
    err.asInstanceOf[AITurnError.MoveFailed].cause shouldBe a[SessionMoveError.DomainRejection]
  }
