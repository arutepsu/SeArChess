package chess.notation.pgn

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PgnNotationExporterSpec extends AnyFlatSpec with Matchers {
  "PgnNotationExporter" should "handle export result types" in {
    val success = chess.notation.api.PgnExportResult.Success("1. e4 e5")
    val failure = chess.notation.api.PgnExportResult.Failure("error")
    success.pgn should include ("e4")
    failure.reason should include ("error")
  }
}
