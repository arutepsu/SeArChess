package chess.adapter.gui.notation

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chess.notation.api._

class GuiNotationApiWarningsSpec extends AnyFlatSpec with Matchers {
  val api = new GuiNotationApi(new NotationFacade[chess.domain.state.GameState] {
    override def parse(format: NotationFormat, input: String) = Left(ParseFailure.StructuralError("fail"))
    override def executeImport(parsed: ParsedNotation, target: ImportTarget) = Left(ImportFailure.MappingError("fail"))
    override def executeExport(data: chess.domain.state.GameState, format: NotationFormat) = Left(ExportFailure.SerializationError("field", "fail"))
  })

  "GuiNotationApi.toGuiWarning" should "map UnknownTag to Informational" in {
    val w = NotationWarning.UnknownTag("TestTag")
    val gw = api.getClass.getDeclaredMethod("toGuiWarning", classOf[NotationWarning])
    gw.setAccessible(true)
    val result = gw.invoke(api, w).asInstanceOf[GuiNotationWarning]
    result.category shouldBe GuiWarningCategory.Informational
  }

  it should "map IgnoredField to Informational" in {
    val w = NotationWarning.IgnoredField("field", "reason")
    val gw = api.getClass.getDeclaredMethod("toGuiWarning", classOf[NotationWarning])
    gw.setAccessible(true)
    val result = gw.invoke(api, w).asInstanceOf[GuiNotationWarning]
    result.category shouldBe GuiWarningCategory.Informational
  }

  it should "map UnsupportedExtensionIgnored to DataLoss" in {
    val w = NotationWarning.UnsupportedExtensionIgnored("ext")
    val gw = api.getClass.getDeclaredMethod("toGuiWarning", classOf[NotationWarning])
    gw.setAccessible(true)
    val result = gw.invoke(api, w).asInstanceOf[GuiNotationWarning]
    result.category shouldBe GuiWarningCategory.DataLoss
  }

  it should "map NormalizationApplied to Normalization" in {
    val w = NotationWarning.NormalizationApplied("desc")
    val gw = api.getClass.getDeclaredMethod("toGuiWarning", classOf[NotationWarning])
    gw.setAccessible(true)
    val result = gw.invoke(api, w).asInstanceOf[GuiNotationWarning]
    result.category shouldBe GuiWarningCategory.Normalization
  }

  it should "map GenericWarning to Informational" in {
    val w = NotationWarning.GenericWarning("msg")
    val gw = api.getClass.getDeclaredMethod("toGuiWarning", classOf[NotationWarning])
    gw.setAccessible(true)
    val result = gw.invoke(api, w).asInstanceOf[GuiNotationWarning]
    result.category shouldBe GuiWarningCategory.Informational
  }
}
