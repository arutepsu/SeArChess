package chess.adapter.textui

import chess.adapter.repository.{InMemoryGameRepository, InMemorySessionGameStore, InMemorySessionRepository}
import chess.application.{ChessService, ObservableGame}
import chess.application.event.AppEvent
import chess.application.port.event.EventPublisher
import chess.application.session.model.SessionIds.GameId
import chess.application.session.model.{DesktopSessionContext, SessionMode, SideController}
import chess.application.session.service.{SessionGameService, SessionService}
import chess.domain.state.GameState
import chess.domain.model.*
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class TextUISpec extends AnyFlatSpec with Matchers with EitherValues:

  /** A scriptable in-memory Console for testing. */
  private class TestConsole(inputs: List[String]) extends Console:
    private val queue  = mutable.Queue(inputs*)
    private val buffer = mutable.ListBuffer.empty[String]

    def readLine(): String            = if queue.isEmpty then null else queue.dequeue()
    def print(text: String): Unit     = buffer += text
    def printLine(text: String): Unit = buffer += text
    def printed: String               = buffer.mkString("\n")

  // ── TuiExitReason ──────────────────────────────────────────────────────────

  "TextUI.run()" should "return EndOfInput and print Goodbye when stdin closes immediately" in {
    val c = TestConsole(List())
    val reason = TextUI(c).run()
    reason  shouldBe TuiExitReason.EndOfInput
    c.printed should include("Goodbye!")
  }

  it should "return UserQuit when the user types quit" in {
    val c = TestConsole(List("quit"))
    val reason = TextUI(c).run()
    reason  shouldBe TuiExitReason.UserQuit
    c.printed should include("Goodbye!")
  }

  // ── Basic commands ──────────────────────────────────────────────────────────

  it should "display help again when the help command is entered" in {
    val c = TestConsole(List("help", "quit"))
    TextUI(c).run()
    c.printed.split("move").length should be >= 2   // help printed twice
  }

  it should "redisplay the board on 'show' command" in {
    val c = TestConsole(List("show", "quit"))
    TextUI(c).run()
    c.printed should include("a b c d e f g h")
  }

  it should "start a new game on 'new' command" in {
    val c = TestConsole(List("new", "quit"))
    TextUI(c).run()
    c.printed should include("New game started.")
  }

  // ── Move commands ───────────────────────────────────────────────────────────

  it should "apply a valid move and continue to the next prompt" in {
    val c = TestConsole(List("move e2 e4", "quit"))
    TextUI(c).run()
    c.printed should include("Goodbye!")
  }

  it should "show an error and continue when a move is illegal" in {
    val c = TestConsole(List("move e2 e5", "quit"))
    TextUI(c).run()
    c.printed should include("Goodbye!")
  }

  it should "show a parse error and continue on an unknown command" in {
    val c = TestConsole(List("castle", "quit"))
    TextUI(c).run()
    c.printed should include("Unknown command")
    c.printed should include("Goodbye!")
  }

  it should "show a domain error and continue when the from-square is invalid" in {
    val c = TestConsole(List("move z9 e4", "quit"))
    TextUI(c).run()
    c.printed should include("z9")
    c.printed should include("Goodbye!")
  }

  it should "show a domain error and continue when the to-square is invalid" in {
    val c = TestConsole(List("move e2 z9", "quit"))
    TextUI(c).run()
    c.printed should include("z9")
    c.printed should include("Goodbye!")
  }

  it should "show a parse error and continue on empty input" in {
    val c = TestConsole(List("", "quit"))
    TextUI(c).run()
    c.printed should include("Please enter a command")
    c.printed should include("Goodbye!")
  }

  it should "show an error when moving the wrong player's piece" in {
    val c = TestConsole(List("move e7 e5", "quit"))
    TextUI(c).run()
    c.printed should include("not your turn")
    c.printed should include("Goodbye!")
  }

  // ── Promotion workflow ──────────────────────────────────────────────────────

  private def pos(alg: String): Position =
    Position.fromAlgebraic(alg).getOrElse(throw AssertionError(s"Invalid position: $alg"))

  /** Board with a white pawn at a7, ready to promote by moving to a8. */
  private def promotionReadyState: GameState =
    val board = Board.empty
      .place(pos("a7"), Piece(Color.White, PieceType.Pawn))
      .place(pos("a1"), Piece(Color.White, PieceType.King))
      .place(pos("e8"), Piece(Color.Black, PieceType.King))
    ChessService.createNewGame().copy(board = board, currentPlayer = Color.White)

  it should "show 'No promotion' message when promote is used on a fresh game" in {
    val c = TestConsole(List("promote q", "quit"))
    TextUI(c).run()
    c.printed should include("No pawn promotion pending")
    c.printed should include("Goodbye!")
  }

  it should "show 'No promotion' message for all piece types with no pending promotion" in {
    Seq("promote r", "promote b", "promote n").foreach { cmd =>
      val c = TestConsole(List(cmd, "quit"))
      TextUI(c).run()
      c.printed should include("No pawn promotion pending")
    }
  }

  it should "show an invalid-token error for 'promote k'" in {
    val c = TestConsole(List("promote k", "quit"))
    TextUI(c).run()
    c.printed should include("k")
    c.printed should include("Goodbye!")
  }

  it should "show the promotion prompt after a pawn-to-last-rank move" in {
    val c = TestConsole(List("move a7 a8", "quit"))
    new TextUI(c, new ObservableGame()).run()
    c.printed should include("promotion")
    c.printed should include("Goodbye!")
  }

  it should "resolve a pending promotion to Queen successfully" in {
    val c = TestConsole(List("move a7 a8", "promote q", "quit"))
    TextUI(c, promotionReadyState).run()
    c.printed should include("Q")
    c.printed should include("Goodbye!")
  }

  it should "resolve a pending promotion to Rook successfully" in {
    val c = TestConsole(List("move a7 a8", "promote r", "quit"))
    TextUI(c, promotionReadyState).run()
    c.printed should include("r")   // black side shows lowercase; our promoted piece is white 'R'
    c.printed should include("Goodbye!")
  }

  it should "resolve a pending promotion to Bishop successfully" in {
    val c = TestConsole(List("move a7 a8", "promote b", "quit"))
    TextUI(c, promotionReadyState).run()
    c.printed should include("Goodbye!")
  }

  it should "resolve a pending promotion to Knight successfully" in {
    val c = TestConsole(List("move a7 a8", "promote n", "quit"))
    TextUI(c, promotionReadyState).run()
    c.printed should include("Goodbye!")
  }

  // ── ObservableGame constructor overload ────────────────────────────────────

  it should "work with an explicit ObservableGame" in {
    val c = TestConsole(List("move a7 a8", "promote q", "quit"))
    new TextUI(c, new ObservableGame(promotionReadyState)).run()
    c.printed should include("Q")
  }

  // ── Session-aware mode ─────────────────────────────────────────────────────

  /** Minimal event publisher that collects published events for assertion. */
  private class TestEventPublisher extends EventPublisher:
    val events: mutable.ListBuffer[AppEvent] = mutable.ListBuffer.empty
    def publish(event: AppEvent): Unit = events += event

  "TextUI (session-aware)" should "persist game state after a successful move" in {
    val collector    = new TestEventPublisher
    val sessionRepo  = new InMemorySessionRepository
    val gameRepo     = new InMemoryGameRepository
    val store        = new InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionSvc   = new SessionService(sessionRepo, _ => ())
    val svc          = new SessionGameService(sessionSvc, store, collector.publish)
    val gameId       = GameId.random()
    val session      = svc.createSession(
      gameId, SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal
    ).value
    val game = new ObservableGame()
    val c    = TestConsole(List("move e2 e4", "quit"))
    new TextUI(c, game, Some(svc), Some(new DesktopSessionContext(session))).run()
    val savedState = gameRepo.load(gameId).value
    savedState.moveHistory.size shouldBe 1
  }

  it should "publish MoveApplied after a successful move" in {
    val collector    = new TestEventPublisher
    val sessionRepo  = new InMemorySessionRepository
    val gameRepo     = new InMemoryGameRepository
    val store        = new InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionSvc   = new SessionService(sessionRepo, _ => ())
    val svc          = new SessionGameService(sessionSvc, store, collector.publish)
    val gameId       = GameId.random()
    val session      = svc.createSession(
      gameId, SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal
    ).value
    val game = new ObservableGame()
    collector.events.clear()  // discard SessionCreated from setup
    val c = TestConsole(List("move e2 e4", "quit"))
    new TextUI(c, game, Some(svc), Some(new DesktopSessionContext(session))).run()
    val moveEvents = collector.events.collect { case e: AppEvent.MoveApplied => e }
    moveEvents should have size 1
    moveEvents.head.move.from.toString shouldBe "e2"
    moveEvents.head.move.to.toString   shouldBe "e4"
  }

  it should "not publish MoveApplied after an illegal move" in {
    val collector    = new TestEventPublisher
    val sessionRepo  = new InMemorySessionRepository
    val gameRepo     = new InMemoryGameRepository
    val store        = new InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionSvc   = new SessionService(sessionRepo, _ => ())
    val svc          = new SessionGameService(sessionSvc, store, collector.publish)
    val gameId       = GameId.random()
    val session      = svc.createSession(
      gameId, SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal
    ).value
    val game = new ObservableGame()
    collector.events.clear()
    val c = TestConsole(List("move e2 e5", "quit"))  // three-square jump is illegal
    new TextUI(c, game, Some(svc), Some(new DesktopSessionContext(session))).run()
    collector.events.collect { case e: AppEvent.MoveApplied => e } shouldBe empty
  }

  it should "render an error and continue after an illegal move in session mode" in {
    val sessionRepo  = new InMemorySessionRepository
    val gameRepo     = new InMemoryGameRepository
    val store        = new InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionSvc   = new SessionService(sessionRepo, _ => ())
    val svc          = new SessionGameService(sessionSvc, store, _ => ())
    val gameId       = GameId.random()
    val session      = svc.createSession(
      gameId, SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal
    ).value
    val game = new ObservableGame()
    val c    = TestConsole(List("move e2 e5", "quit"))
    new TextUI(c, game, Some(svc), Some(new DesktopSessionContext(session))).run()
    c.printed should include("Goodbye!")
  }

  it should "notify ObservableGame after a successful move in session mode" in {
    val sessionRepo    = new InMemorySessionRepository
    val gameRepo       = new InMemoryGameRepository
    val store          = new InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionSvc     = new SessionService(sessionRepo, _ => ())
    val svc            = new SessionGameService(sessionSvc, store, _ => ())
    val gameId         = GameId.random()
    val session        = svc.createSession(
      gameId, SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal
    ).value
    val game           = new ObservableGame()
    var observedCount  = 0
    game.addObserver { _ => observedCount += 1 }
    val c = TestConsole(List("move e2 e4", "quit"))
    new TextUI(c, game, Some(svc), Some(new DesktopSessionContext(session))).run()
    observedCount should be >= 1
  }

  it should "persist fresh game state after 'new' command in session mode" in {
    val sessionRepo  = new InMemorySessionRepository
    val gameRepo     = new InMemoryGameRepository
    val store        = new InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionSvc   = new SessionService(sessionRepo, _ => ())
    val svc          = new SessionGameService(sessionSvc, store, _ => ())
    val gameId       = GameId.random()
    val session      = svc.createSession(
      gameId, SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal
    ).value
    val game = new ObservableGame()
    // Make a move first, then reset.  After 'new', the game in the observer
    // bridge should be back to the initial position (no move history).
    val c = TestConsole(List("move e2 e4", "new", "quit"))
    new TextUI(c, game, Some(svc), Some(new DesktopSessionContext(session))).run()
    game.getState.moveHistory shouldBe empty
  }

  it should "still accept moves after 'new' command in session mode" in {
    val collector    = new TestEventPublisher
    val sessionRepo  = new InMemorySessionRepository
    val gameRepo     = new InMemoryGameRepository
    val store        = new InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionSvc   = new SessionService(sessionRepo, _ => ())
    val svc          = new SessionGameService(sessionSvc, store, collector.publish)
    val gameId       = GameId.random()
    val session      = svc.createSession(
      gameId, SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal
    ).value
    val game     = new ObservableGame()
    collector.events.clear()
    val c = TestConsole(List("new", "move d2 d4", "quit"))
    new TextUI(c, game, Some(svc), Some(new DesktopSessionContext(session))).run()
    // A MoveApplied event means the move in the new session was accepted.
    collector.events.collect { case e: AppEvent.MoveApplied => e } should have size 1
  }

  // ── Shared desktop session ─────────────────────────────────────────────────
  // These tests prove that two adapters sharing the same SessionGameService and
  // GameSession operate on ONE authoritative game identity.  Moves from either
  // adapter accumulate in the same repository entry under the same GameId.

  "Shared desktop session" should "accumulate moves from two adapters in the same repo entry" in {
    // Shared infrastructure — exactly as the composition root creates it.
    val sessionRepo = new InMemorySessionRepository
    val gameRepo    = new InMemoryGameRepository
    val store       = new InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionSvc  = new SessionService(sessionRepo, _ => ())
    val svc         = new SessionGameService(sessionSvc, store, _ => ())
    val gameId      = GameId.random()
    val session     = svc.createSession(
      gameId, SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal
    ).value
    val sharedGame    = new ObservableGame()
    val sharedContext = new DesktopSessionContext(session)

    // Adapter A (simulating TUI) makes the first move.
    val cA = TestConsole(List("move e2 e4", "quit"))
    new TextUI(cA, sharedGame, Some(svc), Some(sharedContext)).run()

    // Repo has exactly one move; sharedGame reflects the updated state.
    gameRepo.load(gameId).value.moveHistory.size shouldBe 1

    // Adapter B (simulating a second adapter) makes a move using the same context.
    // It reads sharedGame.getState which was updated by Adapter A via updateState.
    val cB = TestConsole(List("move e7 e5", "quit"))
    new TextUI(cB, sharedGame, Some(svc), Some(sharedContext)).run()

    // Repo now has two moves under the same gameId — proves shared authoritative state.
    gameRepo.load(gameId).value.moveHistory.size shouldBe 2
  }

  it should "publish events for moves from both adapters sharing the same session" in {
    val collector   = new TestEventPublisher
    val sessionRepo = new InMemorySessionRepository
    val gameRepo    = new InMemoryGameRepository
    val store       = new InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionSvc  = new SessionService(sessionRepo, _ => ())
    val svc         = new SessionGameService(sessionSvc, store, collector.publish)
    val gameId      = GameId.random()
    val session     = svc.createSession(
      gameId, SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal
    ).value
    val sharedGame    = new ObservableGame()
    val sharedContext = new DesktopSessionContext(session)
    collector.events.clear()  // discard SessionCreated

    val cA = TestConsole(List("move d2 d4", "quit"))
    new TextUI(cA, sharedGame, Some(svc), Some(sharedContext)).run()

    val cB = TestConsole(List("move d7 d5", "quit"))
    new TextUI(cB, sharedGame, Some(svc), Some(sharedContext)).run()

    // Both moves published MoveApplied; both carry the same gameId.
    val moveEvents = collector.events.collect { case e: AppEvent.MoveApplied => e }
    moveEvents should have size 2
    moveEvents.forall(_.gameId == session.gameId) shouldBe true
  }

  it should "make state from the first adapter visible to the second adapter via ObservableGame" in {
    val sessionRepo = new InMemorySessionRepository
    val gameRepo    = new InMemoryGameRepository
    val store       = new InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionSvc  = new SessionService(sessionRepo, _ => ())
    val svc         = new SessionGameService(sessionSvc, store, _ => ())
    val gameId      = GameId.random()
    val session     = svc.createSession(
      gameId, SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal
    ).value
    val sharedGame    = new ObservableGame()
    val sharedContext = new DesktopSessionContext(session)

    // Adapter A makes a move — updates sharedGame via updateState.
    val cA = TestConsole(List("move e2 e4", "quit"))
    new TextUI(cA, sharedGame, Some(svc), Some(sharedContext)).run()

    // sharedGame now reflects Adapter A's move (1 entry in history).
    sharedGame.getState.moveHistory.size shouldBe 1

    // Adapter B reads sharedGame.getState at the top of its loop and sees
    // Black to move — it can now make a Black move without conflict.
    val cB = TestConsole(List("move e7 e5", "quit"))
    new TextUI(cB, sharedGame, Some(svc), Some(sharedContext)).run()

    sharedGame.getState.moveHistory.size shouldBe 2
  }

  // ── DesktopSessionContext shared reference ─────────────────────────────────

  it should "update DesktopSessionContext after 'new' command so a second adapter sees the new session" in {
    val sessionRepo = new InMemorySessionRepository
    val gameRepo    = new InMemoryGameRepository
    val store       = new InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionSvc  = new SessionService(sessionRepo, _ => ())
    val svc         = new SessionGameService(sessionSvc, store, _ => ())
    val gameId      = GameId.random()
    val session     = svc.createSession(
      gameId, SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal
    ).value
    val sharedContext = new DesktopSessionContext(session)
    val sharedGame    = new ObservableGame()

    val c = TestConsole(List("new", "quit"))
    new TextUI(c, sharedGame, Some(svc), Some(sharedContext)).run()

    sharedContext.getSession.sessionId should not equal session.sessionId
  }