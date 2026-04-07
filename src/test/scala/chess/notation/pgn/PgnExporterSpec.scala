package chess.notation.pgn

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PgnExporterSpec extends AnyFlatSpec with Matchers {
  "PgnExporter" should "export moves to PGN string" in {
    val moves = List("e4", "e5", "Nf3", "Nc6")
    val pgn = PgnExporter.exportMoves(moves)
    pgn should include ("e4")
    pgn should include ("Nc6")
  }
}
