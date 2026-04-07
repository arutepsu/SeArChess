package chess.notation.pgn

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PgnNotationImporterSpec extends AnyFlatSpec with Matchers {
  "PgnNotationImporter" should "be instantiable" in {
    noException should be thrownBy PgnNotationImporter
  }
}
