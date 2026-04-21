package chess.application.ai.service

import chess.adapter.ai.LocalDeterministicAiClient
import chess.adapter.repository.{
  InMemoryGameRepository,
  InMemorySessionGameStore,
  InMemorySessionRepository
}
import chess.application.ChessService
import chess.application.port.ai.{AIError, AiMoveSuggestionClient, AIRequestContext, AIResponse}
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.application.session.service.{SessionGameService, SessionMoveError, SessionService}
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
    val sessionRepo = InMemorySessionRepository()
    val gameRepo = InMemoryGameRepository()
    val store = InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionService = SessionService(sessionRepo, _ => ())
    val svc = SessionGameService(sessionService, store, _ => ())
    val gameId = GameId.random()
    val session = svc
      .createSession(
        gameId = gameId,
        mode = SessionMode.HumanVsAI,
        whiteController = whiteController,
        blackController = blackController
      )
      .value
    val state = GameStateFactory.initial()
    val aiService = AITurnService(LocalDeterministicAiClient(), svc, _ => ())
    (aiService, session, state, svc, gameRepo)

  // ── happy path ─────────────────────────────────────────────────────────────

  "AITurnService.requestAIMove" should "return an updated GameState after a legal AI move" in {
    val (aiService, session, state, _, _) = freshSetup()
    val (nextState, _) = aiService.requestAIMove(session, state).value
    nextState.moveHistory.size shouldBe 1
  }

  it should "transition the session lifecycle from Created to Active after the first AI move" in {
    val (aiService, session, state, _, _) = freshSetup()
    val (_, updatedSession) = aiService.requestAIMove(session, state).value
    updatedSession.lifecycle shouldBe SessionLifecycle.Active
  }

  it should "switch the current player to Black after White AI moves" in {
    val (aiService, session, state, _, _) = freshSetup()
    val (nextState, _) = aiService.requestAIMove(session, state).value
    nextState.currentPlayer shouldBe chess.domain.model.Color.Black
  }

  it should "persist the updated GameState to GameRepository after a successful AI move" in {
    val (aiService, session, state, _, gameRepo) = freshSetup()
    val (nextState, _) = aiService.requestAIMove(session, state).value
    gameRepo.load(session.gameId).value shouldBe nextState
  }

  // ── not AI turn ────────────────────────────────────────────────────────────

  it should "return NotAITurn when the side to move is human-controlled" in {
    // White is HumanLocal, it is White's turn → AI service must refuse
    val (aiService, session, state, _, _) =
      freshSetup(whiteController = SideController.HumanLocal, blackController = SideController.AI())
    aiService.requestAIMove(session, state).left.value shouldBe AITurnError.NotAITurn
  }

  // ── provider failure ───────────────────────────────────────────────────────

  it should "return ProviderFailure(NoLegalMove) when the provider signals no legal moves" in {
    val noMoveProvider = new AiMoveSuggestionClient:
      def suggestMove(context: AIRequestContext) = Left(AIError.NoLegalMove)
    val sessionRepo = InMemorySessionRepository()
    val gameRepo = InMemoryGameRepository()
    val store = InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionService = SessionService(sessionRepo, _ => ())
    val svc = SessionGameService(sessionService, store, _ => ())
    val session = svc
      .createSession(
        gameId = GameId.random(),
        mode = SessionMode.HumanVsAI,
        whiteController = SideController.AI(),
        blackController = SideController.HumanLocal
      )
      .value
    val state = GameStateFactory.initial()
    val aiService = AITurnService(noMoveProvider, svc, _ => ())
    aiService.requestAIMove(session, state).left.value shouldBe
      AITurnError.ProviderFailure(AIError.NoLegalMove)
  }

  it should "return ProviderFailure(EngineFailure) when the provider signals an engine error" in {
    val crashProvider = new AiMoveSuggestionClient:
      def suggestMove(context: AIRequestContext) =
        Left(AIError.EngineFailure("timeout"))
    val sessionRepo = InMemorySessionRepository()
    val gameRepo = InMemoryGameRepository()
    val store = InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionService = SessionService(sessionRepo, _ => ())
    val svc = SessionGameService(sessionService, store, _ => ())
    val session = svc
      .createSession(
        gameId = GameId.random(),
        mode = SessionMode.HumanVsAI,
        whiteController = SideController.AI(),
        blackController = SideController.HumanLocal
      )
      .value
    val state = GameStateFactory.initial()
    val aiService = AITurnService(crashProvider, svc, _ => ())
    aiService.requestAIMove(session, state).left.value shouldBe
      AITurnError.ProviderFailure(AIError.EngineFailure("timeout"))
  }

  // ── move path not bypassed ─────────────────────────────────────────────────

  it should "return IllegalSuggestedMove when the provider suggests a move outside Game legal moves" in {
    // A pawn cannot jump from e2 to e5 — this verifies that the AI move goes
    // through the normal domain validation path and is not applied blindly.
    val illegalProvider = new AiMoveSuggestionClient:
      def suggestMove(context: AIRequestContext) =
        Right(AIResponse(Move(e2, e5)))
    val sessionRepo = InMemorySessionRepository()
    val gameRepo = InMemoryGameRepository()
    val store = InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionService = SessionService(sessionRepo, _ => ())
    val svc = SessionGameService(sessionService, store, _ => ())
    val session = svc
      .createSession(
        gameId = GameId.random(),
        mode = SessionMode.HumanVsAI,
        whiteController = SideController.AI(),
        blackController = SideController.HumanLocal
      )
      .value
    val state = GameStateFactory.initial()
    val aiService = AITurnService(illegalProvider, svc, _ => ())
    val err = aiService.requestAIMove(session, state).left.value
    err shouldBe AITurnError.IllegalSuggestedMove(Move(e2, e5))
  }
