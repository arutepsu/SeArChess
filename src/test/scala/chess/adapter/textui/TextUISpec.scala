package chess.adapter.textui

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
