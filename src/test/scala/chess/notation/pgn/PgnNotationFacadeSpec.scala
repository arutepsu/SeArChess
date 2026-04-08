package chess.notation.pgn

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chess.domain.state.GameState
import chess.notation.api._

class PgnNotationFacadeSpec extends AnyFlatSpec with Matchers {
  "PgnNotationFacade" should "export a simple game as PGN" in {
    val state = chess.application.ChessService.createNewGame()
    // Simuliere einen Zug: e2 nach e4 (nur als Beispiel, Move-Objekt muss passen)
    val move = chess.domain.model.Move(
      chess.domain.model.Position.fromAlgebraic("e2").toOption.get,
      chess.domain.model.Position.fromAlgebraic("e4").toOption.get,
      None
    )
    val stateAfter = chess.application.ChessService.handleCommand(state, chess.application.ChessCommand.MakeMove(move)).toOption.get
    val result = PgnNotationFacade.executeExport(stateAfter, NotationFormat.PGN)
    result.isRight shouldBe true
    val pgn = result.toOption.get.text
    pgn should include ("e2")
    pgn should include ("e4")
  }

  it should "return unsupported for export to JSON" in {
    val state = chess.application.ChessService.createNewGame()
    val result = PgnNotationFacade.executeExport(state, NotationFormat.JSON)
    result.isLeft shouldBe true
    result.left.get shouldBe a [ExportFailure.UnsupportedExportFormat]
  }

  it should "return IncompatibleTarget for PositionTarget import" in {
    val parsed = ParsedNotation.ParsedPgn("", Map.empty, "e2 e4")
    val result = PgnNotationFacade.executeImport(parsed, ImportTarget.PositionTarget)
    result.isLeft shouldBe true
    result.left.get shouldBe a [ImportFailure.IncompatibleTarget]
  }

  it should "return MappingError for invalid move string" in {
    val parsed = ParsedNotation.ParsedPgn("", Map.empty, "invalidmove")
    val result = PgnNotationFacade.executeImport(parsed, ImportTarget.GameTarget)
    result.isLeft shouldBe true
    result.left.get shouldBe a [ImportFailure.MappingError]
  }

  it should "return unavailable for import" in {
    val parsed = ParsedNotation.ParsedPgn("", Map.empty, "e4 e5")
    val result = PgnNotationFacade.executeImport(parsed, ImportTarget.GameTarget)
    result.isLeft shouldBe true
    result.left.get shouldBe a [ImportFailure.MappingError]
  }

  it should "return StructuralError for unknown format" in {
    val result = PgnNotationFacade.parse(NotationFormat.FEN, "irrelevant")
    result.isLeft shouldBe true
    result.left.get shouldBe a [ParseFailure.StructuralError]
    result.left.get.asInstanceOf[ParseFailure.StructuralError].message should include ("No parser for format")
  }
}
