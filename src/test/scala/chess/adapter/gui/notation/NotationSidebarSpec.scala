package chess.adapter.gui.notation

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chess.adapter.gui.notation.NotationSidebar
import chess.adapter.gui.notation.GuiNotationWarning
import chess.adapter.gui.notation.GuiWarningCategory

class NotationSidebarWarningsSpec extends AnyFlatSpec with Matchers {

  "updateWarningsBox" should "hide warningsBox when list is empty" in {
    val box = new scalafx.scene.layout.VBox()

    NotationSidebar.updateWarningsBox(box, Nil)

    box.visible.value shouldBe false
    box.children.size shouldBe 0
  }

  it should "show a label for each warning" in {
    val box = new scalafx.scene.layout.VBox()

    val warnings = List(
      GuiNotationWarning("Warnung 1", GuiWarningCategory.DataLoss),
      GuiNotationWarning("Warnung 2", GuiWarningCategory.Normalization)
    )

    NotationSidebar.updateWarningsBox(box, warnings)

    box.visible.value shouldBe true
    box.children.size shouldBe 2

    val texts =
      box.children.collect {
        case l: javafx.scene.control.Label => l.getText
      }

    texts.exists(_.contains("Warnung 1")) shouldBe true
    texts.exists(_.contains("Warnung 2")) shouldBe true
  }

  it should "clear previous warnings before adding new ones" in {
    val box = new scalafx.scene.layout.VBox()

    val first =
      List(GuiNotationWarning("Alt", GuiWarningCategory.DataLoss))

    val second =
      List(GuiNotationWarning("Neu", GuiWarningCategory.Normalization))

    NotationSidebar.updateWarningsBox(box, first)
    box.children.size shouldBe 1

    NotationSidebar.updateWarningsBox(box, second)

    box.children.size shouldBe 1

    val text =
      box.children.head
        .asInstanceOf[javafx.scene.control.Label]
        .getText

    text should include("Neu")
  }
}