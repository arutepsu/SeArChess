package chess.adapter.textui

import chess.application.{ChessService, ObservableGame}
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