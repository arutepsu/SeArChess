package chess.notation.pgn

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PgnNotationApiSpec extends AnyFlatSpec with Matchers {
  "PgnNotationApi" should "be instantiable and have basic structure" in {
    noException should be thrownBy PgnNotationApi
  }
}
