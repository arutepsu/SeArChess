package chess.adapter.textui

import chess.adapter.textui.{ConsoleIO, TextUI}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}

class TextUIMainSpec extends AnyFlatSpec with Matchers:

  "runChessTextUi" should "start the game loop and exit cleanly on quit" in {
    val input  = new ByteArrayInputStream("quit\n".getBytes)
    val output = new ByteArrayOutputStream()
    scala.Console.withIn(input) {
      scala.Console.withOut(new PrintStream(output)) {
        runChessTextUi()
      }
    }
    output.toString should include("Goodbye!")
  }
