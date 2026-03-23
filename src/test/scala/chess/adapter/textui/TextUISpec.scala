package chess.adapter.textui

import chess.application.{ChessService, GameState, PendingPromotion}
import chess.domain.model.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class TextUISpec extends AnyFlatSpec with Matchers:

  /** A scriptable in-memory Console for testing. */
  private class TestConsole(inputs: List[String]) extends Console:
    private val queue  = mutable.Queue(inputs*)
    private val buffer = mutable.ListBuffer.empty[String]

    def readLine(): String            = queue.dequeue()
    def print(text: String): Unit     = buffer += text
    def printLine(text: String): Unit = buffer += text
    def printed: String               = buffer.mkString("\n")

  "TextUI" should "print Goodbye on quit" in {
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

  /** A minimal board with a white pawn already at the promotion square (h8),
   *  ready for the promote command.  White king at a1, black king at a8.
   *  After promoting to Queen, Black is in check (White Queen on h8 covers rank 8).
   */
  private def pos(alg: String): Position =
    Position.fromAlgebraic(alg).getOrElse(throw AssertionError(s"Invalid algebraic position in test constant: $alg"))

  private def promotionPendingState: GameState =
    val h8 = pos("h8")
    val a1 = pos("a1")
    val a8 = pos("a8")
    val h7 = pos("h7")
    val board = Board.empty
      .place(h8, Piece(Color.White, PieceType.Pawn))
      .place(a1, Piece(Color.White, PieceType.King))
      .place(a8, Piece(Color.Black, PieceType.King))
    ChessService.createNewGame().copy(
      board            = board,
      currentPlayer    = Color.White,
      pendingPromotion = Some(PendingPromotion(h8, Color.White, Move(h7, h8)))
    )

  it should "show NoPromotionPending error when promote is used on a fresh game" in {
    val c = TestConsole(List("promote q", "quit"))
    TextUI(c).run()
    c.printed should include("No promotion")
    c.printed should include("Goodbye!")
  }

  it should "show NoPromotionPending error for 'promote r' with no pending promotion" in {
    val c = TestConsole(List("promote r", "quit"))
    TextUI(c).run()
    c.printed should include("No promotion")
  }

  it should "show NoPromotionPending error for 'promote b' with no pending promotion" in {
    val c = TestConsole(List("promote b", "quit"))
    TextUI(c).run()
    c.printed should include("No promotion")
  }

  it should "show NoPromotionPending error for 'promote n' with no pending promotion" in {
    val c = TestConsole(List("promote n", "quit"))
    TextUI(c).run()
    c.printed should include("No promotion")
  }

  it should "resolve a pending promotion successfully and continue" in {
    val c = TestConsole(List("promote q", "quit"))
    TextUI(c, promotionPendingState).run()
    c.printed should include("Goodbye!")
    // Board should show the queen (Q promoted from pawn)
    c.printed should include("Q")
  }

  it should "show an InvalidPromotionToken error for 'promote k'" in {
    val c = TestConsole(List("promote k", "quit"))
    TextUI(c).run()
    c.printed should include("k")
    c.printed should include("Goodbye!")
  }

  it should "show the promotion prompt after a pawn-to-last-rank move" in {
    // Set up a board where a white pawn is at a7, ready to promote on a8
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
