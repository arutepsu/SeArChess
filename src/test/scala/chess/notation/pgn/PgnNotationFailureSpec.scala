package chess.notation.pgn

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PgnNotationFailureSpec extends AnyFlatSpec with Matchers {
  "PgnNotationFailure" should "support all failure cases" in {
    val invalid = PgnNotationFailure.InvalidFormat
    val noMoves = PgnNotationFailure.NoMovesFound
    val other   = PgnNotationFailure.Other("reason")
    invalid shouldBe a [PgnNotationFailure]
    noMoves shouldBe a [PgnNotationFailure]
    other.reason shouldBe ("reason")
  }
}
