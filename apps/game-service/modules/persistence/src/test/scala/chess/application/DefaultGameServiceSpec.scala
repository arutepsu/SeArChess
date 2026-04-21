package chess.application

import chess.adapter.event.CollectingEventPublisher
import chess.adapter.repository.{
  InMemoryGameRepository,
  InMemorySessionGameStore,
  InMemorySessionRepository
}
import chess.application.ai.service.{AITurnError, AITurnService}
import chess.application.event.AppEvent
import chess.application.port.ai.{AIError, AiMoveSuggestionClient, AIResponse}
import chess.application.port.repository.RepositoryError
import chess.application.session.model.{SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.application.session.service.{
  SessionError,
  SessionGameService,
  SessionMoveError,
  SessionService
}
import chess.domain.model.{Color, GameStatus, Move, Position}
import chess.domain.state.{GameState, GameStateFactory}
import org.scalatest.{EitherValues, OptionValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Unit tests for [[DefaultGameService]].
  *
  * All storage is in-memory. Tests are deterministic and require no network or file I/O. The
  * [[CollectingEventPublisher]] is used to verify event publication side-effects.
  *
  * Coverage scope:
  *   - [[DefaultGameService.createGame]] — delegates to commands.newGame
  *   - [[DefaultGameService.submitMove]] — loads session+state; publishes MoveRejected on domain
  *     rejection
  *   - [[DefaultGameService.resignGame]] — loads session+state; delegates to commands.resignGame
  *   - [[DefaultGameService.cancelSession]] — delegates to sessionService.cancelSession
  *   - [[DefaultGameService.triggerAIMove]] — NotAITurn when aiService=None; delegates when Some
  *   - Query delegation: getSession, getSessionByGameId, getGame, listActiveSessions
  */
class DefaultGameServiceSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  // ── Shared fixture ────────────────────────────────────────────────────────

  private def freshFixture(
      collector: CollectingEventPublisher = CollectingEventPublisher(),
      aiService: Option[AITurnService] = None
  ): (
      DefaultGameService,
      InMemorySessionRepository,
      InMemoryGameRepository,
      CollectingEventPublisher
  ) =
    val sessionRepo = new InMemorySessionRepository
    val gameRepo = new InMemoryGameRepository
    val store = new InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionService = new SessionService(sessionRepo, collector)
    val commands = new SessionGameService(sessionService, store, collector)
    val svc = DefaultGameService(
      commands = commands,
      sessionService = sessionService,
      gameRepository = gameRepo,
      publisher = collector,
      aiService = aiService
    )
    (svc, sessionRepo, gameRepo, collector)

  /** Create a game through the service and return the result. */
  private def createGame(svc: DefaultGameService) =
    svc.createGame(SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal)

  // e2→e4 — always legal from the initial position
  private val e2 = Position.from(4, 1).value
  private val e4 = Position.from(4, 3).value
  private val validFirstMove = Move(e2, e4)

  // e2→e6 — illegal jump; rejected by domain
  private val e6 = Position.from(4, 5).value
  private val illegalMove = Move(e2, e6)

  // ── createGame ────────────────────────────────────────────────────────────

  "DefaultGameService.createGame" should "return a fresh game state and a Created session" in {
    val (svc, _, _, _) = freshFixture()
    val (state, session) = createGame(svc).value
    state.moveHistory shouldBe empty
    session.lifecycle shouldBe SessionLifecycle.Created
    session.mode shouldBe SessionMode.HumanVsHuman
  }

  it should "persist the session and game state" in {
    val (svc, sessionRepo, gameRepo, _) = freshFixture()
    val (_, session) = createGame(svc).value
    sessionRepo.load(session.sessionId).isRight shouldBe true
    gameRepo.load(session.gameId).isRight shouldBe true
  }

  it should "publish SessionCreated" in {
    val collector = CollectingEventPublisher()
    val (svc, _, _, _) = freshFixture(collector)
    val (_, session) = createGame(svc).value
    collector.events.collectFirst { case e: AppEvent.SessionCreated => e }.value.sessionId shouldBe
      session.sessionId
  }

  // ── submitMove ────────────────────────────────────────────────────────────

  "DefaultGameService.submitMove" should "apply a legal move and return updated state and session" in {
    val (svc, _, gameRepo, _) = freshFixture()
    val (_, session) = createGame(svc).value
    val (nextState, _) =
      svc.submitMove(session.gameId, validFirstMove, SideController.HumanLocal).value
    nextState.moveHistory.size shouldBe 1
    gameRepo.load(session.gameId).value.moveHistory.size shouldBe 1
  }

  it should "return SessionMoveError.DomainRejection for an illegal move" in {
    val (svc, _, _, _) = freshFixture()
    val (_, session) = createGame(svc).value
    val err = svc.submitMove(session.gameId, illegalMove, SideController.HumanLocal).left.value
    err shouldBe a[SessionMoveError.DomainRejection]
  }

  it should "publish MoveRejected when the domain rejects the move" in {
    val collector = CollectingEventPublisher()
    val (svc, _, _, _) = freshFixture(collector)
    val (_, session) = createGame(svc).value
    collector.clear()
    svc.submitMove(session.gameId, illegalMove, SideController.HumanLocal)
    val rejected = collector.events.collectFirst { case e: AppEvent.MoveRejected => e }
    rejected shouldBe defined
    rejected.value.gameId shouldBe session.gameId
    rejected.value.move shouldBe illegalMove
  }

  it should "not publish MoveRejected when the game is not found" in {
    val collector = CollectingEventPublisher()
    val (svc, _, _, _) = freshFixture(collector)
    svc.submitMove(GameId.random(), validFirstMove, SideController.HumanLocal)
    collector.events.collect { case e: AppEvent.MoveRejected => e } shouldBe empty
  }

  // ── resignGame ────────────────────────────────────────────────────────────

  "DefaultGameService.resignGame" should "set Resigned status and finish the session when White resigns" in {
    val (svc, _, gameRepo, _) = freshFixture()
    val (_, session) = createGame(svc).value
    val (finalState, finalSession) = svc.resignGame(session.sessionId, Color.White).value
    finalState.status shouldBe GameStatus.Resigned(Color.Black)
    finalSession.lifecycle shouldBe SessionLifecycle.Finished
    gameRepo.load(session.gameId).value.status shouldBe GameStatus.Resigned(Color.Black)
  }

  it should "set Resigned(White) when Black resigns" in {
    val (svc, _, _, _) = freshFixture()
    val (_, session) = createGame(svc).value
    val (finalState, _) = svc.resignGame(session.sessionId, Color.Black).value
    finalState.status shouldBe GameStatus.Resigned(Color.White)
  }

  it should "publish GameResigned with the winning side" in {
    val collector = CollectingEventPublisher()
    val (svc, _, _, _) = freshFixture(collector)
    val (_, session) = createGame(svc).value
    collector.clear()
    svc.resignGame(session.sessionId, Color.White)
    val resigned = collector.events.collectFirst { case e: AppEvent.GameResigned => e }
    resigned shouldBe defined
    resigned.value.winner shouldBe Color.Black
  }

  it should "return SessionError for an unknown session id" in {
    val (svc, _, _, _) = freshFixture()
    val unknownId = SessionId.random()
    svc.resignGame(unknownId, Color.White).isLeft shouldBe true
  }

  it should "return InvalidLifecycleTransition when the session is already Finished" in {
    val (svc, _, _, _) = freshFixture()
    val (_, session) = createGame(svc).value
    svc.resignGame(session.sessionId, Color.White)
    val err = svc.resignGame(session.sessionId, Color.White).left.value
    err shouldBe a[SessionError.InvalidLifecycleTransition]
  }

  // ── cancelSession ─────────────────────────────────────────────────────────

  "DefaultGameService.cancelSession" should "advance the session lifecycle to Cancelled" in {
    val (svc, sessionRepo, _, _) = freshFixture()
    val (_, session) = createGame(svc).value
    val updated = svc.cancelSession(session.sessionId).value
    updated.lifecycle shouldBe SessionLifecycle.Cancelled
    sessionRepo.load(session.sessionId).value.lifecycle shouldBe SessionLifecycle.Cancelled
  }

  it should "publish SessionCancelled" in {
    val collector = CollectingEventPublisher()
    val (svc, _, _, _) = freshFixture(collector)
    val (_, session) = createGame(svc).value
    collector.clear()
    svc.cancelSession(session.sessionId)
    val cancelled = collector.events.collectFirst { case e: AppEvent.SessionCancelled => e }
    cancelled shouldBe defined
    cancelled.value.sessionId shouldBe session.sessionId
  }

  it should "return SessionError for an unknown session id" in {
    val (svc, _, _, _) = freshFixture()
    svc.cancelSession(SessionId.random()).isLeft shouldBe true
  }

  it should "return InvalidLifecycleTransition when the session is already Cancelled" in {
    val (svc, _, _, _) = freshFixture()
    val (_, session) = createGame(svc).value
    svc.cancelSession(session.sessionId)
    val err = svc.cancelSession(session.sessionId).left.value
    err shouldBe a[SessionError.InvalidLifecycleTransition]
  }

  // ── triggerAIMove ─────────────────────────────────────────────────────────

  "DefaultGameService.triggerAIMove" should "return NotConfigured when no AI service is wired" in {
    val (svc, _, _, _) = freshFixture(aiService = None)
    val (_, session) = createGame(svc).value
    svc.triggerAIMove(session.sessionId).left.value shouldBe AITurnError.NotConfigured
  }

  it should "return SessionLookupFailed when the session is unknown (even with AI configured)" in {
    val collector = CollectingEventPublisher()
    val sessionRepo = new InMemorySessionRepository
    val gameRepo = new InMemoryGameRepository
    val store = new InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionService = new SessionService(sessionRepo, _ => ())
    val commands = new SessionGameService(sessionService, store, collector)
    val alwaysLegal: AiMoveSuggestionClient =
      _ => Right(AIResponse(Move(Position.from(4, 1).value, Position.from(4, 3).value)))
    val ai = AITurnService(alwaysLegal, commands, collector)
    val svcWithAI = DefaultGameService(commands, sessionService, gameRepo, collector, Some(ai))
    svcWithAI.triggerAIMove(SessionId.random()).left.value shouldBe
      a[AITurnError.SessionLookupFailed]
  }

  // ── triggerAIMoveByGameId ─────────────────────────────────────────────────

  "DefaultGameService.triggerAIMoveByGameId" should "return NotConfigured when no AI service is wired" in {
    val (svc, _, _, _) = freshFixture(aiService = None)
    val (_, session) = createGame(svc).value
    svc.triggerAIMoveByGameId(session.gameId).left.value shouldBe AITurnError.NotConfigured
  }

  it should "return SessionLookupFailed for an unknown game id (even with AI configured)" in {
    val collector = CollectingEventPublisher()
    val sessionRepo = new InMemorySessionRepository
    val gameRepo = new InMemoryGameRepository
    val store = new InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionService = new SessionService(sessionRepo, _ => ())
    val commands = new SessionGameService(sessionService, store, collector)
    val alwaysLegal: AiMoveSuggestionClient =
      _ => Right(AIResponse(Move(Position.from(4, 1).value, Position.from(4, 3).value)))
    val ai = AITurnService(alwaysLegal, commands, collector)
    val svcWithAI = DefaultGameService(commands, sessionService, gameRepo, collector, Some(ai))
    svcWithAI.triggerAIMoveByGameId(GameId.random()).left.value shouldBe
      a[AITurnError.SessionLookupFailed]
  }

  // ── Queries ───────────────────────────────────────────────────────────────

  "DefaultGameService.getSession" should "return the session for a known id" in {
    val (svc, _, _, _) = freshFixture()
    val (_, session) = createGame(svc).value
    svc.getSession(session.sessionId).value.sessionId shouldBe session.sessionId
  }

  it should "return SessionError for an unknown id" in {
    val (svc, _, _, _) = freshFixture()
    svc.getSession(SessionId.random()).isLeft shouldBe true
  }

  "DefaultGameService.getSessionByGameId" should "return the session for a known game id" in {
    val (svc, _, _, _) = freshFixture()
    val (_, session) = createGame(svc).value
    svc.getSessionByGameId(session.gameId).value.sessionId shouldBe session.sessionId
  }

  it should "return SessionError for an unknown game id" in {
    val (svc, _, _, _) = freshFixture()
    svc.getSessionByGameId(GameId.random()).isLeft shouldBe true
  }

  "DefaultGameService.getGame" should "return a GameView for the persisted game state" in {
    val (svc, _, _, _) = freshFixture()
    val (initialState, session) = createGame(svc).value
    val view = svc.getGame(session.gameId).value
    view.gameId shouldBe session.gameId
    view.moveHistory shouldBe initialState.moveHistory
    view.currentPlayer shouldBe initialState.currentPlayer
    view.legalMoves should have size 20
  }

  it should "return RepositoryError for an unknown game id" in {
    val (svc, _, _, _) = freshFixture()
    svc.getGame(GameId.random()).isLeft shouldBe true
  }

  "DefaultGameService.getLegalMoves" should "return legal moves for the current player" in {
    val (svc, _, _, _) = freshFixture()
    val (_, session) = createGame(svc).value
    val legal = svc.getLegalMoves(session.gameId).value

    legal.gameId shouldBe session.gameId
    legal.currentPlayer shouldBe Color.White
    legal.moves should have size 20
    legal.moves.map(m => (m.from.toString, m.to.toString)) should contain allOf (
      ("e2", "e3"),
      ("e2", "e4"),
      ("g1", "f3")
    )
  }

  it should "return RepositoryError for legal moves of an unknown game id" in {
    val (svc, _, _, _) = freshFixture()
    svc.getLegalMoves(GameId.random()).isLeft shouldBe true
  }

  "DefaultGameService.listActiveSessions" should "return an empty list when no sessions exist" in {
    val (svc, _, _, _) = freshFixture()
    svc.listActiveSessions().value shouldBe empty
  }

  it should "include a newly created session in the active list" in {
    val (svc, _, _, _) = freshFixture()
    val (_, session) = createGame(svc).value
    val active = svc.listActiveSessions().value
    active.map(_.sessionId) should contain(session.sessionId)
  }

  it should "exclude a cancelled session from the active list" in {
    val (svc, _, _, _) = freshFixture()
    val (_, session) = createGame(svc).value
    svc.cancelSession(session.sessionId)
    val active = svc.listActiveSessions().value
    active.map(_.sessionId) should not contain session.sessionId
  }

  it should "exclude a resigned session from the active list" in {
    val (svc, _, _, _) = freshFixture()
    val (_, session) = createGame(svc).value
    svc.resignGame(session.sessionId, Color.White)
    val active = svc.listActiveSessions().value
    active.map(_.sessionId) should not contain session.sessionId
  }

  it should "reflect multiple concurrent sessions accurately" in {
    val (svc, _, _, _) = freshFixture()
    val (_, s1) = createGame(svc).value
    val (_, s2) = createGame(svc).value
    val (_, s3) = createGame(svc).value
    svc.cancelSession(s2.sessionId)
    val active = svc.listActiveSessions().value.map(_.sessionId)
    active should contain(s1.sessionId)
    active should not contain s2.sessionId
    active should contain(s3.sessionId)
  }
