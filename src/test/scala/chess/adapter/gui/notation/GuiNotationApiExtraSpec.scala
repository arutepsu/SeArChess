package chess.adapter.gui.notation

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chess.domain.state.GameState
import chess.notation.api._

class GuiNotationApiExtraSpec extends AnyFlatSpec with Matchers {
  val api = new GuiNotationApi(new NotationFacade[GameState] {
    override def parse(format: NotationFormat, input: String) = Left(ParseFailure.StructuralError("fail"))
    override def executeImport(parsed: ParsedNotation, target: ImportTarget) = Left(ImportFailure.IncompatibleTarget(ParsedNotationKind.Fen, ImportTarget.PositionTarget, "fail"))
    override def executeExport(data: GameState, format: NotationFormat) = Left(ExportFailure.SerializationError("field", "fail"))
  })
  val dummyState = chess.application.ChessService.createNewGame()

  "GuiNotationApi" should "handle parse failure as invalid input" in {
    val result = api.importFen("bad")
    result shouldBe a [GuiNotationOutcome.Failure]
    result.asInstanceOf[GuiNotationOutcome.Failure].category shouldBe FailureCategory.InvalidInput
  }

  it should "handle import failure as semantic error" in {
    val result = api.importFen("bad")
    result shouldBe a [GuiNotationOutcome.Failure]
    result.asInstanceOf[GuiNotationOutcome.Failure].category should not be FailureCategory.UnavailableFeature
  }

  it should "handle export failure as semantic error" in {
    val result = api.exportFen(dummyState)
    result shouldBe a [GuiNotationOutcome.Failure]
    result.asInstanceOf[GuiNotationOutcome.Failure].category shouldBe FailureCategory.SemanticError
  }
}
