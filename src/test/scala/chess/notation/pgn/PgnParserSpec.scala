package chess.notation.pgn

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PgnParserSpec extends AnyFlatSpec with Matchers {
  "PgnParser" should "parse a simple move list" in {
    val moves = "e4 e5 Nf3 Nc6"
    val result = PgnParser.parseMoves(moves)
    result.isRight shouldBe true
    result.foreach { moveList =>
      moveList should not be empty
    }
  }
}
