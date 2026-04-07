package chess.adapter.textui

import chess.application.ChessService
import chess.domain.state.GameState
import chess.domain.model.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class TextUISpec extends AnyFlatSpec with Matchers:

  /** A scriptable in-memory Console for testing. */
  private class TestConsole(inputs: List[String]) extends Console:
    private val queue  = mutable.Queue(inputs*)
    private val buffer = mutable.ListBuffer.empty[String]

    def readLine(): String            = if queue.isEmpty then null else queue.dequeue()
    def print(text: String): Unit     = buffer += text
    def printLine(text: String): Unit = buffer += text
    def printed: String               = buffer.mkString("\n")

  "TextUI" should "print Goodbye and stop when EOF is signalled (null input)" in {
    // Empty queue simulates stdin closing — readLine() returns null
    val c = TestConsole(List())
    TextUI(c).run()
    c.printed should include("Goodbye!")
  }

  it should "print Goodbye on quit" in {
    val c = TestConsole(List("quit"))
    TextUI(c).run()
    c.printed should include("Goodbye!")
  }

  it should "display help again when the help command is entered" in {
    val c = TestConsole(List("help", "quit"))
    TextUI(c).run()
    // renderHelp is printed at startup AND again on the 'help' command
    c.printed.split("quit").length should be >= 2
    c.printed should include("move")
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

  it should "apply a valid move and continue to the next prompt" in {
    val c = TestConsole(List("move e2 e4", "quit"))
    TextUI(c).run()
    c.printed should include("Goodbye!")
  }

  it should "show an error and continue when a move is illegal" in {
    // e2 to e5 is not a legal pawn move
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
    // Covers the Position.fromAlgebraic(fromStr) Left branch
    val c = TestConsole(List("move z9 e4", "quit"))
    TextUI(c).run()
    c.printed should include("z9")
    c.printed should include("Goodbye!")
  }

  it should "show a domain error and continue when the to-square is invalid" in {
    // Covers the Position.fromAlgebraic(toStr) Left branch (from is valid, to is not)
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
    // On a fresh game it is White's turn; try to move a black pawn
    val c = TestConsole(List("move e7 e5", "quit"))
    TextUI(c).run()
    c.printed should include("not your turn")
    c.printed should include("Goodbye!")
  }

  // ── Promotion workflow ─────────────────────────────────────────────────────

  private def pos(alg: String): Position =
    Position.fromAlgebraic(alg).getOrElse(throw AssertionError(s"Invalid algebraic position in test constant: $alg"))

  /** Board with a white pawn at a7, ready to promote by moving to a8. */
  private def promotionReadyState: GameState =
    val board = Board.empty
      .place(pos("a7"), Piece(Color.White, PieceType.Pawn))
      .place(pos("a1"), Piece(Color.White, PieceType.King))
      .place(pos("e8"), Piece(Color.Black, PieceType.King))
    ChessService.createNewGame().copy(board = board, currentPlayer = Color.White)

  it should "show 'No pawn promotion' message when promote is used on a fresh game" in {
    val c = TestConsole(List("promote q", "quit"))
    TextUI(c).run()
    c.printed should include("No pawn promotion")
    c.printed should include("Goodbye!")
  }

  it should "show 'No pawn promotion' message for 'promote r' with no pending promotion" in {
    val c = TestConsole(List("promote r", "quit"))
    TextUI(c).run()
    c.printed should include("No pawn promotion")
  }

  it should "show 'No pawn promotion' message for 'promote b' with no pending promotion" in {
    val c = TestConsole(List("promote b", "quit"))
    TextUI(c).run()
    c.printed should include("No pawn promotion")
  }

  it should "show 'No pawn promotion' message for 'promote n' with no pending promotion" in {
    val c = TestConsole(List("promote n", "quit"))
    TextUI(c).run()
    c.printed should include("No pawn promotion")
  }

  it should "show the promotion prompt after a pawn-to-last-rank move" in {
    val c = TestConsole(List("move a7 a8", "quit"))
    TextUI(c, promotionReadyState).run()
    c.printed should include("promotion")
    c.printed should include("Goodbye!")
  }

  it should "resolve a pending promotion successfully after move then promote" in {
    val c = TestConsole(List("move a7 a8", "promote q", "quit"))
    TextUI(c, promotionReadyState).run()
    c.printed should include("Goodbye!")
    // Board should show the queen (Q promoted from pawn)
    c.printed should include("Q")
  }

  it should "show the promotion prompt after a pawn-to-last-rank move (separate board setup)" in {
    val board = Board.empty
      .place(pos("a7"), Piece(Color.White, PieceType.Pawn))
      .place(pos("a1"), Piece(Color.White, PieceType.King))
      .place(pos("e8"), Piece(Color.Black, PieceType.King))
    val state = ChessService.createNewGame().copy(board = board, currentPlayer = Color.White)
    val c = TestConsole(List("move a7 a8", "promote q", "quit"))
    TextUI(c, state).run()
    c.printed should include("promotion")
    c.printed should include("Goodbye!")
  }

  it should "show an InvalidPromotionToken error for 'promote k'" in {
    val c = TestConsole(List("promote k", "quit"))
    TextUI(c).run()
    c.printed should include("k")
    c.printed should include("Goodbye!")
  }

  it should "show an application error and keep the pending promotion when promotion resolution fails" in {
    val c = TestConsole(List("promote q", "quit"))
    val ui = TextUI(c)

    val pendingPromotionMove = Move(pos("a7"), pos("a8"))
    val inconsistentState = ChessService.createNewGame()

    val loopMethod = classOf[TextUI]
      .getDeclaredMethods
      .find(_.getName.contains("loop"))
      .getOrElse(fail("Could not find private loop method via reflection"))

    loopMethod.setAccessible(true)
    loopMethod.invoke(ui, inconsistentState, Some(pendingPromotionMove))

    c.printed should not include("No pawn promotion is pending.")
    c.printed should include("Goodbye!")
  }