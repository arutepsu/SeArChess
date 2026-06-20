package chess.application.ai.service

import chess.adapter.ai.LocalDeterministicAiClient
import chess.adapter.repository.{
  InMemoryGameRepository,
  InMemorySessionGameStore,
  InMemorySessionRepository
}
import chess.application.port.ai.{AIError, AiMoveSuggestionClient, AIRequestContext, AIResponse}
import chess.application.session.model.{GameSession, SessionMode, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.application.session.service.{SessionGameCommandService, SessionLifecycleService}
import chess.domain.model.{Color, GameStatus}
import chess.domain.state.GameStateFactory
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AITurnRunTurnsSpec extends AnyFlatSpec with Matchers with EitherValues:

  private def freshSetup(
      whiteController: SideController,
      blackController: SideController,
      mode: SessionMode = SessionMode.HumanVsAI
  ) =
    val sessionRepo = InMemorySessionRepository()
    val gameRepo    = InMemoryGameRepository()
    val store       = InMemorySessionGameStore(sessionRepo, gameRepo)
    val lifecycle   = SessionLifecycleService(sessionRepo, _ => ())
    val svc         = SessionGameCommandService(lifecycle, store, _ => ())
    val gameId      = GameId.random()
    val session     = lifecycle
      .createSession(gameId, mode, whiteController, blackController)
      .value
    val state   = GameStateFactory.initial()
    val service = AITurnService(LocalDeterministicAiClient(), svc, _ => ())
    (service, session, state, gameRepo)

  // ── AwaitingHuman: human-vs-human stops immediately ────────────────────────

  "AITurnService.runTurns" should
    "stop immediately with AwaitingHuman when neither side is AI-controlled" in {
      val (service, session, state, _) =
        freshSetup(SideController.HumanLocal, SideController.HumanLocal, SessionMode.HumanVsHuman)
      val result = service.runTurns(session, state, maxPlies = 5)
      result.pliesRun shouldBe 0
      result.stopReason shouldBe AITurnRunStopReason.AwaitingHuman
      result.state shouldBe state
    }

  // ── AwaitingHuman: HumanVsAI runs one AI ply then yields back ─────────────

  it should "run one AI ply in HumanVsAI (white=Human, black=AI) then stop at AwaitingHuman" in {
    // White is human; it is White's turn → runTurns stops immediately (White controls).
    val (service, session, state, _) =
      freshSetup(SideController.HumanLocal, SideController.AI())
    val result = service.runTurns(session, state, maxPlies = 5)
    result.pliesRun shouldBe 0
    result.stopReason shouldBe AITurnRunStopReason.AwaitingHuman
  }

  it should "run exactly one AI ply after a human move in HumanVsAI (white=Human, black=AI)" in {
    val sessionRepo = InMemorySessionRepository()
    val gameRepo    = InMemoryGameRepository()
    val store       = InMemorySessionGameStore(sessionRepo, gameRepo)
    val lifecycle   = SessionLifecycleService(sessionRepo, _ => ())
    val svc         = SessionGameCommandService(lifecycle, store, _ => ())
    val gameId      = GameId.random()
    val session0    = lifecycle
      .createSession(gameId, SessionMode.HumanVsAI, SideController.HumanLocal, SideController.AI())
      .value
    val state0  = GameStateFactory.initial()
    val service = AITurnService(LocalDeterministicAiClient(), svc, _ => ())

    // Simulate white human move (e2→e4) through the commands boundary.
    import chess.domain.model.{Move, Position}
    val e2   = Position.from(4, 1).value
    val e4   = Position.from(4, 3).value
    val (state1, session1) = svc
      .submitMove(session0, state0, Move(e2, e4), SideController.HumanLocal)
      .value

    // Now it is Black's turn (AI).  runTurns should apply one AI move and stop.
    val result = service.runTurns(session1, state1, maxPlies = 5)
    result.pliesRun shouldBe 1
    result.stopReason shouldBe AITurnRunStopReason.AwaitingHuman
    result.state.moveHistory.size shouldBe 2
    result.state.currentPlayer shouldBe Color.White
  }

  // ── MaxPliesReached: AIVsAI respects the cap ──────────────────────────────

  it should "stop at MaxPliesReached in AIVsAI when maxPlies is consumed" in {
    val (service, session, state, _) =
      freshSetup(SideController.AI(), SideController.AI(), SessionMode.AIVsAI)
    val result = service.runTurns(session, state, maxPlies = 3)
    result.pliesRun shouldBe 3
    result.stopReason shouldBe AITurnRunStopReason.MaxPliesReached
    result.state.moveHistory.size shouldBe 3
  }

  it should "run fewer than maxPlies in AIVsAI when maxPlies=1" in {
    val (service, session, state, _) =
      freshSetup(SideController.AI(), SideController.AI(), SessionMode.AIVsAI)
    val result = service.runTurns(session, state, maxPlies = 1)
    result.pliesRun shouldBe 1
    result.stopReason shouldBe AITurnRunStopReason.MaxPliesReached
  }

  // ── GameFinished: stops when state is already terminal ────────────────────

  it should "stop immediately with GameFinished when the initial state is already terminal" in {
    val (service, session, _, _) =
      freshSetup(SideController.AI(), SideController.HumanLocal)
    val terminalState = GameStateFactory.initial().copy(status = GameStatus.Checkmate(Color.White))
    val result = service.runTurns(session, terminalState, maxPlies = 5)
    result.pliesRun shouldBe 0
    result.stopReason shouldBe AITurnRunStopReason.GameFinished
    result.state.status shouldBe GameStatus.Checkmate(Color.White)
  }

  // ── MoveFailed: provider failure is captured as a stop reason ─────────────

  it should "stop with MoveFailed when the AI provider fails on the first ply" in {
    val failingProvider = new AiMoveSuggestionClient:
      def suggestMove(ctx: AIRequestContext): Either[AIError, AIResponse] =
        Left(AIError.EngineFailure("crash"))

    val sessionRepo = InMemorySessionRepository()
    val gameRepo    = InMemoryGameRepository()
    val store       = InMemorySessionGameStore(sessionRepo, gameRepo)
    val lifecycle   = SessionLifecycleService(sessionRepo, _ => ())
    val svc         = SessionGameCommandService(lifecycle, store, _ => ())
    val session     = lifecycle
      .createSession(GameId.random(), SessionMode.AIVsAI, SideController.AI(), SideController.AI())
      .value
    val state   = GameStateFactory.initial()
    val service = AITurnService(failingProvider, svc, _ => ())

    val result = service.runTurns(session, state, maxPlies = 5)
    result.pliesRun shouldBe 0
    result.stopReason shouldBe a[AITurnRunStopReason.MoveFailed]
    result.stopReason match
      case AITurnRunStopReason.MoveFailed(AITurnError.ProviderFailure(AIError.EngineFailure(msg))) =>
        msg shouldBe "crash"
      case other => fail(s"unexpected stop reason: $other")
  }

  it should "record pliesRun correctly when the provider fails on the third ply" in {
    var callCount = 0
    val failOnThird = new AiMoveSuggestionClient:
      def suggestMove(ctx: AIRequestContext): Either[AIError, AIResponse] =
        callCount += 1
        if callCount < 3 then
          // Return a valid legal move for the first two calls.
          chess.domain.rules.GameStateRules
            .legalMoves(ctx.state)
            .toSeq
            .sortBy(m => (m.from.file, m.from.rank, m.to.file, m.to.rank))
            .headOption match
            case Some(move) => Right(AIResponse(move))
            case None       => Left(AIError.NoLegalMove)
        else Left(AIError.EngineFailure("crash on ply 3"))

    val sessionRepo = InMemorySessionRepository()
    val gameRepo    = InMemoryGameRepository()
    val store       = InMemorySessionGameStore(sessionRepo, gameRepo)
    val lifecycle   = SessionLifecycleService(sessionRepo, _ => ())
    val svc         = SessionGameCommandService(lifecycle, store, _ => ())
    val session     = lifecycle
      .createSession(GameId.random(), SessionMode.AIVsAI, SideController.AI(), SideController.AI())
      .value
    val state   = GameStateFactory.initial()
    val service = AITurnService(failOnThird, svc, _ => ())

    val result = service.runTurns(session, state, maxPlies = 10)
    result.pliesRun shouldBe 2
    result.stopReason shouldBe a[AITurnRunStopReason.MoveFailed]
  }
