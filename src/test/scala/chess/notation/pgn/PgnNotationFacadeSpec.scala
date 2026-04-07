package chess.notation.pgn

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chess.domain.state.GameState
import chess.notation.api._

class PgnNotationFacadeSpec extends AnyFlatSpec with Matchers {
  "PgnNotationFacade" should "return unsupported for export" in {
    val state = chess.application.ChessService.createNewGame()
    val result = PgnNotationFacade.executeExport(state, NotationFormat.PGN)
    result.isLeft shouldBe true
    result.left.get shouldBe a [ExportFailure.UnsupportedExportFormat]
  }

  it should "return unavailable for import" in {
    val parsed = ParsedNotation.ParsedPgn("", Map.empty, "e4 e5")
    val result = PgnNotationFacade.executeImport(parsed, ImportTarget.GameTarget)
    result.isLeft shouldBe true
    result.left.get shouldBe a [ImportFailure.MappingError]
  }
}
