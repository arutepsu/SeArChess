package chess.application.ai.service

import chess.adapter.ai.LocalDeterministicAiClient
import chess.adapter.event.CollectingEventPublisher
import chess.adapter.repository.{InMemoryGameRepository, InMemorySessionGameStore, InMemorySessionRepository}
import chess.application.event.AppEvent
import chess.application.port.ai.{AIError, AiMoveSuggestionClient, AIRequestContext, AIResponse}
import chess.application.session.model.{SessionMode, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.application.session.service.{SessionGameService, SessionService}
import chess.domain.model.{Move, Position}
import chess.domain.state.GameStateFactory
import org.scalatest.{EitherValues, OptionValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AITurnServiceEventSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  private def freshSetup(provider: AiMoveSuggestionClient = LocalDeterministicAiClient()) =
    val collector      = CollectingEventPublisher()
    val sessionRepo    = InMemorySessionRepository()
    val gameRepo       = InMemoryGameRepository()
    val store          = InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionService = SessionService(sessionRepo, _ => ())
    val svc            = SessionGameService(sessionService, store, _ => ())
    val session        = svc.createSession(
      gameId          = GameId.random(),
      mode            = SessionMode.HumanVsAI,
      whiteController = SideController.AI(),
      blackController = SideController.HumanLocal
    ).value
    val state     = GameStateFactory.initial()
    val aiService = AITurnService(provider, svc, collector)
    (aiService, collector, session, state)

  // ── AITurnRequested ────────────────────────────────────────────────────────

  "AITurnService.requestAIMove" should "publish AITurnRequested when the guard passes" in {
    val (aiService, collector, session, state) = freshSetup()
    aiService.requestAIMove(session, state)
    val requested = collector.events.collectFirst { case e: AppEvent.AITurnRequested => e }
    requested shouldBe defined
    requested.value.sessionId     shouldBe session.sessionId
    requested.value.gameId        shouldBe session.gameId
    requested.value.currentPlayer shouldBe chess.domain.model.Color.White
  }

  it should "NOT publish AITurnRequested when the guard fails (not AI turn)" in {
    val collector      = CollectingEventPublisher()
    val sessionRepo    = InMemorySessionRepository()
    val gameRepo       = InMemoryGameRepository()
    val store          = InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionService = SessionService(sessionRepo, _ => ())
    val svc            = SessionGameService(sessionService, store, _ => ())
    val humanSession   = svc.createSession(
      gameId          = GameId.random(),
      mode            = SessionMode.HumanVsHuman,
      whiteController = SideController.HumanLocal,
      blackController = SideController.HumanLocal
    ).value
    val state     = GameStateFactory.initial()
    val aiService = AITurnService(LocalDeterministicAiClient(), svc, collector)
    aiService.requestAIMove(humanSession, state)
    collector.events should not contain a[AppEvent.AITurnRequested]
  }

  // ── AITurnCompleted ────────────────────────────────────────────────────────

  it should "publish AITurnCompleted after a successful AI move" in {
    val (aiService, collector, session, state) = freshSetup()
    aiService.requestAIMove(session, state)
    val completed = collector.events.collectFirst { case e: AppEvent.AITurnCompleted => e }
    completed shouldBe defined
    completed.value.sessionId shouldBe session.sessionId
    completed.value.gameId    shouldBe session.gameId
  }

  it should "not publish AITurnFailed on a successful move" in {
    val (aiService, collector, session, state) = freshSetup()
    aiService.requestAIMove(session, state)
    collector.events.collect { case e: AppEvent.AITurnFailed => e } shouldBe empty
  }

  // ── AITurnFailed: provider failure ────────────────────────────────────────

  it should "publish AITurnFailed when the provider returns NoLegalMove" in {
    val noMoveProvider = new AiMoveSuggestionClient:
      def suggestMove(context: AIRequestContext) = Left(AIError.NoLegalMove)
    val (aiService, collector, session, state) = freshSetup(noMoveProvider)
    aiService.requestAIMove(session, state)
    val failed = collector.events.collectFirst { case e: AppEvent.AITurnFailed => e }
    failed shouldBe defined
    failed.value.reason should include("no legal moves")
  }

  it should "publish AITurnFailed when the provider returns EngineFailure" in {
    val crashProvider = new AiMoveSuggestionClient:
      def suggestMove(context: AIRequestContext) = Left(AIError.EngineFailure("timeout"))
    val (aiService, collector, session, state) = freshSetup(crashProvider)
    aiService.requestAIMove(session, state)
    val failed = collector.events.collectFirst { case e: AppEvent.AITurnFailed => e }
    failed shouldBe defined
    failed.value.reason should include("timeout")
  }

  // ── AITurnFailed: illegal move ────────────────────────────────────────────

  it should "publish AITurnFailed when the provider returns an illegal move" in {
    val e2 = Position.from(4, 1).value
    val e5 = Position.from(4, 4).value
    val illegalProvider = new AiMoveSuggestionClient:
      def suggestMove(context: AIRequestContext) = Right(AIResponse(Move(e2, e5)))
    val (aiService, collector, session, state) = freshSetup(illegalProvider)
    aiService.requestAIMove(session, state)
    val failed = collector.events.collectFirst { case e: AppEvent.AITurnFailed => e }
    failed shouldBe defined
    failed.value.reason should include("illegal AI suggestion")
  }

  // ── event ordering ────────────────────────────────────────────────────────

  it should "publish AITurnRequested before AITurnCompleted on a successful move" in {
    val (aiService, collector, session, state) = freshSetup()
    aiService.requestAIMove(session, state)
    val types = collector.events.map(_.getClass.getSimpleName)
    types.indexOf("AITurnRequested") should be < types.indexOf("AITurnCompleted")
  }
