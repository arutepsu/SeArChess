package chess.application.session.service

import chess.adapter.event.CollectingEventPublisher
import chess.adapter.repository.{InMemoryGameRepository, InMemorySessionGameStore, InMemorySessionRepository}
import chess.application.event.AppEvent
import chess.application.port.event.EventPublisher
import chess.application.port.repository.{RepositoryError, SessionGameStore}
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.domain.model.{Board, Color, GameStatus, Move, Piece, PieceType, Position}
import chess.domain.state.{CastlingRights, GameState, GameStateFactory}
import org.scalatest.{EitherValues, OptionValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Verifies that [[SessionGameService]] publishes events only after the combined
 *  session + game-state write has succeeded, and that both writes are visible
 *  in the repositories when events fire.
 */
class SessionGameServiceSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  // ── Helpers ────────────────────────────────────────────────────────────────

  private def freshFixture(collector: CollectingEventPublisher = CollectingEventPublisher()) =
    val sessionRepo = new InMemorySessionRepository
    val gameRepo    = new InMemoryGameRepository
    val store       = new InMemorySessionGameStore(sessionRepo, gameRepo)
    val service     = new SessionService(sessionRepo, _ => ())
    val svc         = new SessionGameService(service, store, collector)
    (svc, sessionRepo, gameRepo, collector)

  private def createSession(svc: SessionGameService) =
    svc.createSession(
      gameId          = GameId.random(),
      mode            = SessionMode.HumanVsHuman,
      whiteController = SideController.HumanLocal,
      blackController = SideController.HumanLocal
    ).value

  /** Minimal one-move-to-checkmate position (same layout as SessionServiceEventSpec). */
  private val rb1 = Position.from(1, 0).value  // b1
  private val ra1 = Position.from(0, 0).value  // a1
  private val kc7 = Position.from(2, 6).value  // c7
  private val ka8 = Position.from(0, 7).value  // a8

  private def checkmateInOneState: GameState =
    val board = Board.empty
      .place(rb1, Piece(Color.White, PieceType.Rook))
      .place(kc7, Piece(Color.White, PieceType.King))
      .place(ka8, Piece(Color.Black, PieceType.King))
    GameState(
      board          = board,
      currentPlayer  = Color.White,
      moveHistory    = Nil,
      status         = GameStatus.Ongoing(false),
      castlingRights = CastlingRights.none,
      enPassantState = None
    )

  // ── submitMove events ─────────────────────────────────────────────────────

  "SessionGameService.submitMove" should "publish MoveApplied after a successful move" in {
    val (svc, _, _, collector) = freshFixture()
    val session                = createSession(svc)
    val state                  = GameStateFactory.initial()
    val move                   = Move(Position.from(4, 1).value, Position.from(4, 3).value) // e2-e4
    svc.submitMove(session, state, move, SideController.HumanLocal)
    val events = collector.events
    events.collect { case e: AppEvent.MoveApplied => e } should have size 1
    events.collectFirst { case e: AppEvent.MoveApplied => e }.value shouldBe
      AppEvent.MoveApplied(session.sessionId, session.gameId, move, Color.White)
  }

  it should "publish SessionLifecycleChanged when the lifecycle advances (Created → Active)" in {
    val (svc, _, _, collector) = freshFixture()
    val session                = createSession(svc)  // lifecycle: Created
    val state                  = GameStateFactory.initial()
    val move                   = Move(Position.from(4, 1).value, Position.from(4, 3).value)
    svc.submitMove(session, state, move, SideController.HumanLocal)
    val changed = collector.events.collectFirst { case e: AppEvent.SessionLifecycleChanged => e }
    changed.value shouldBe AppEvent.SessionLifecycleChanged(
      session.sessionId, session.gameId,
      SessionLifecycle.Created, SessionLifecycle.Active)
  }

  it should "publish GameFinished when the move results in checkmate" in {
    val (svc, _, _, collector) = freshFixture()
    val session                = createSession(svc)
    val move                   = Move(rb1, ra1)
    svc.submitMove(session, checkmateInOneState, move, SideController.HumanLocal)
    val finished = collector.events.collectFirst { case e: AppEvent.GameFinished => e }
    finished shouldBe defined
    finished.value.sessionId shouldBe session.sessionId
    finished.value.gameId    shouldBe session.gameId
    finished.value.status    shouldBe a[GameStatus.Checkmate]
  }

  it should "not publish any events when the move is rejected" in {
    val (svc, _, _, collector) = freshFixture()
    val session                = createSession(svc)
    val state                  = GameStateFactory.initial()
    val illegalMove            = Move(Position.from(4, 1).value, Position.from(4, 5).value) // e2→e6
    svc.submitMove(session, state, illegalMove, SideController.HumanLocal)
    collector.events shouldBe empty
  }

  it should "call store.save before publishing MoveApplied" in {
    // Verify ordering: the combined write completes before any event fires.
    var storeCallCount = 0
    var writeCountAtPublish = -1  // value of storeCallCount when MoveApplied fired

    val sessionRepo = new InMemorySessionRepository
    val gameRepo    = new InMemoryGameRepository

    val countingStore = new SessionGameStore:
      private val inner = new InMemorySessionGameStore(sessionRepo, gameRepo)
      override def save(session: GameSession, state: GameState): Either[RepositoryError, Unit] =
        storeCallCount += 1
        inner.save(session, state)

    val orderingPublisher: EventPublisher = event =>
      event match
        case _: AppEvent.MoveApplied => writeCountAtPublish = storeCallCount
        case _ => ()

    val service = new SessionService(sessionRepo, _ => ())
    val svc     = new SessionGameService(service, countingStore, orderingPublisher)
    val session = svc.createSession(
      GameId.random(), SessionMode.HumanVsHuman,
      SideController.HumanLocal, SideController.HumanLocal
    ).value

    val move = Move(Position.from(4, 1).value, Position.from(4, 3).value)
    svc.submitMove(session, GameStateFactory.initial(), move, SideController.HumanLocal)

    writeCountAtPublish shouldBe 1  // store.save was called exactly once before MoveApplied fired
  }

  it should "persist the updated game state to the game repository" in {
    val (svc, _, gameRepo, _) = freshFixture()
    val session               = createSession(svc)
    val state                 = GameStateFactory.initial()
    val move                  = Move(Position.from(4, 1).value, Position.from(4, 3).value)
    svc.submitMove(session, state, move, SideController.HumanLocal)
    val saved = gameRepo.load(session.gameId).value
    saved.moveHistory.size shouldBe 1
  }

  it should "persist the updated session lifecycle to the session repository" in {
    val (svc, sessionRepo, _, _) = freshFixture()
    val session                  = createSession(svc)
    val state                    = GameStateFactory.initial()
    val move                     = Move(Position.from(4, 1).value, Position.from(4, 3).value)
    svc.submitMove(session, state, move, SideController.HumanLocal)
    val saved = sessionRepo.load(session.sessionId).value
    saved.lifecycle shouldBe SessionLifecycle.Active
  }

  // ── newGame events ────────────────────────────────────────────────────────

  "SessionGameService.newGame" should "publish SessionCreated on success" in {
    val (svc, _, _, collector) = freshFixture()
    val result                 = svc.newGame(SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal)
    result.isRight shouldBe true
    val (_, session) = result.value
    val events = collector.events
    events should have size 1
    events.head shouldBe AppEvent.SessionCreated(
      session.sessionId, session.gameId, session.mode,
      session.whiteController, session.blackController)
  }

  it should "persist the initial game state to the game repository" in {
    val (svc, _, gameRepo, _) = freshFixture()
    val (_, session)          = svc.newGame(
      SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal
    ).value
    gameRepo.load(session.gameId).isRight shouldBe true
  }

  it should "persist the session to the session repository" in {
    val (svc, sessionRepo, _, _) = freshFixture()
    val (_, session)             = svc.newGame(
      SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal
    ).value
    sessionRepo.load(session.sessionId).isRight shouldBe true
  }
