// $COVERAGE-OFF$ — JavaFX widget; excluded from coverage instrumentation
package chess.adapter.gui.notation

import javafx.application.Platform
import javafx.scene.control.{Button => JButton, Label => JLabel, TextArea => JTextArea}
import javafx.scene.layout.{VBox => JVBox}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chess.application.ChessService
import chess.domain.state.GameState

/** Tests for [[NotationSidebar]] — layout, dispatch, and refresh behaviour.
 *
 *  All assertions that touch JavaFX nodes run on the FX Application Thread via
 *  [[onFx]], which blocks until the runLater body completes.  Construction of
 *  the sidebar itself must also happen on the FX thread.
 *
 *  The test does NOT fork a Stage; it only initialises the Platform, which is
 *  sufficient for creating and exercising ScalaFX nodes in unit tests.
 */
class NotationSidebarSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  // ── JavaFX Platform lifecycle ────────────────────────────────────────────────

  override def beforeAll(): Unit =
    val latch = new CountDownLatch(1)
    try Platform.startup(() => latch.countDown())
    catch case _: IllegalStateException => latch.countDown() // already started
    assert(latch.await(10, TimeUnit.SECONDS), "JavaFX Platform failed to start")

  /** Run `body` on the FX Application Thread and return its result. */
  private def onFx[A](body: => A): A =
    @volatile var result: Option[A] = None
    val latch = new CountDownLatch(1)
    Platform.runLater { () =>
      result = Some(body)
      latch.countDown()
    }
    assert(latch.await(5, TimeUnit.SECONDS), "JavaFX runLater timed out")
    result.get

  // ── Fixture helpers ──────────────────────────────────────────────────────────

  private val InitialFen    = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val freshState    = ChessService.createNewGame()

  private def makeController(
    onRefresh:       NotationSidebarState => Unit = _ => ()
  ): NotationSidebarController =
    new NotationSidebarController(
      api             = GuiNotationApi.default,
      stateProvider   = () => freshState,
      onImportedState = _ => (),
      onRefresh       = onRefresh
    )

  /** Must be called on the FX thread. */
  private def makeSidebar(
    controller:  NotationSidebarController,
    descriptors: Seq[NotationActionDescriptor] = NotationActionDescriptor.defaults
  ): NotationSidebar =
    new NotationSidebar(controller, descriptors)

  // ── Child accessors (index-based, matching root children order) ──────────────

  // Children in root:
  //   0 — sectionHeader("Notation")
  //   1 — inputArea  (TextArea)
  //   2 — importRow  (VBox of Import buttons)
  //   3 — sectionHeader("Export")
  //   4 — exportRow  (VBox of Export buttons)
  //   5 — outputArea (TextArea)
  //   6 — feedbackLabel (Label)
  //   7 — warningsBox (VBox)

  private def rootNode(s: NotationSidebar, i: Int): javafx.scene.Node =
    s.root.children.get(i)

  private def headerLabel(s: NotationSidebar, i: Int): JLabel =
    rootNode(s, i).asInstanceOf[JLabel]

  private def inputArea(s: NotationSidebar): JTextArea =
    rootNode(s, 1).asInstanceOf[JTextArea]

  private def importVBox(s: NotationSidebar): JVBox =
    rootNode(s, 2).asInstanceOf[JVBox]

  private def exportVBox(s: NotationSidebar): JVBox =
    rootNode(s, 4).asInstanceOf[JVBox]

  private def outputArea(s: NotationSidebar): JTextArea =
    rootNode(s, 5).asInstanceOf[JTextArea]

  private def feedbackLabel(s: NotationSidebar): JLabel =
    rootNode(s, 6).asInstanceOf[JLabel]

  private def warningsVBox(s: NotationSidebar): JVBox =
    rootNode(s, 7).asInstanceOf[JVBox]

  private def buttons(vbox: JVBox): Seq[JButton] =
    vbox.getChildren.toArray.collect { case b: JButton => b }.toSeq

  // ── Root layout ──────────────────────────────────────────────────────────────

  "NotationSidebar root" should "have prefWidth 220" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    onFx { sidebar.root.prefWidth.value shouldBe 220.0 }
  }

  it should "have exactly 8 children" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    onFx { sidebar.root.children should have size 8 }
  }

  it should "have padding of 12 on all sides" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    onFx {
      val insets = sidebar.root.padding.value
      insets.getTop    shouldBe 12.0
      insets.getRight  shouldBe 12.0
      insets.getBottom shouldBe 12.0
      insets.getLeft   shouldBe 12.0
    }
  }

  it should "apply the dark background style" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    onFx { sidebar.root.style.value should include("2a2623") }
  }

  // ── Section headers ──────────────────────────────────────────────────────────

  "NotationSidebar section headers" should "show 'Notation' at child index 0" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    onFx { headerLabel(sidebar, 0).getText shouldBe "Notation" }
  }

  it should "show 'Export' at child index 3" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    onFx { headerLabel(sidebar, 3).getText shouldBe "Export" }
  }

  // ── Import buttons ───────────────────────────────────────────────────────────

  "NotationSidebar import buttons" should "render two buttons in the import VBox" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    onFx { buttons(importVBox(sidebar)) should have size 2 }
  }

  it should "label the first import button 'Import FEN'" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    onFx { buttons(importVBox(sidebar))(0).getText shouldBe "Import FEN" }
  }

  it should "label the second import button 'Import PGN'" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    onFx { buttons(importVBox(sidebar))(1).getText shouldBe "Import PGN" }
  }

  it should "set maxWidth to MaxValue on import buttons" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    onFx {
      buttons(importVBox(sidebar)).foreach { b =>
        b.getMaxWidth shouldBe Double.MaxValue
      }
    }
  }

  // ── Export buttons ───────────────────────────────────────────────────────────

  "NotationSidebar export buttons" should "render two buttons in the export VBox" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    onFx { buttons(exportVBox(sidebar)) should have size 2 }
  }

  it should "label the first export button 'Export FEN'" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    onFx { buttons(exportVBox(sidebar))(0).getText shouldBe "Export FEN" }
  }

  it should "label the second export button 'Export PGN'" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    onFx { buttons(exportVBox(sidebar))(1).getText shouldBe "Export PGN" }
  }

  it should "set maxWidth to MaxValue on export buttons" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    onFx {
      buttons(exportVBox(sidebar)).foreach { b =>
        b.getMaxWidth shouldBe Double.MaxValue
      }
    }
  }

  // ── Input area prompt ────────────────────────────────────────────────────────

  "NotationSidebar input area" should "have a prompt text mentioning FEN and PGN" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    onFx {
      val prompt = inputArea(sidebar).getPromptText
      prompt should include("FEN")
      prompt should include("PGN")
    }
  }

  // ── Initial visibility ───────────────────────────────────────────────────────

  "NotationSidebar initial state" should "hide the feedback label" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    onFx { feedbackLabel(sidebar).isVisible shouldBe false }
  }

  it should "hide the warnings box" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    onFx { warningsVBox(sidebar).isVisible shouldBe false }
  }

  // ── Input onChange dispatch ──────────────────────────────────────────────────

  "NotationSidebar input area" should "dispatch InputTextChanged to the controller on text change" in {
    val ctrl    = makeController()
    val sidebar = onFx { makeSidebar(ctrl) }
    onFx { inputArea(sidebar).setText("hello fen") }
    ctrl.currentState.inputText shouldBe "hello fen"
  }

  it should "dispatch InputTextChanged with the updated value on each keystroke" in {
    val ctrl    = makeController()
    val sidebar = onFx { makeSidebar(ctrl) }
    onFx { inputArea(sidebar).setText("a") }
    onFx { inputArea(sidebar).setText("ab") }
    ctrl.currentState.inputText shouldBe "ab"
  }

  // ── Button click dispatch ────────────────────────────────────────────────────

  "NotationSidebar Import FEN button" should "dispatch FenImport action and produce parse feedback" in {
    val ctrl    = makeController()
    val sidebar = onFx { makeSidebar(ctrl) }
    // Leave inputText empty → import will fail with InvalidInput
    onFx { buttons(importVBox(sidebar))(0).fire() }
    ctrl.currentState.feedback should matchPattern {
      case Some(SidebarFeedback.Failure(_, _, FailureCategory.InvalidInput)) =>
    }
  }

  it should "succeed and clear feedback when a valid FEN is in the input" in {
    val ctrl    = makeController()
    val sidebar = onFx { makeSidebar(ctrl) }
    onFx { inputArea(sidebar).setText(InitialFen) }
    onFx { buttons(importVBox(sidebar))(0).fire() }
    ctrl.currentState.feedback shouldBe Some(SidebarFeedback.Success("Position imported successfully."))
  }

  "NotationSidebar Import PGN button" should "dispatch PgnImport and produce UnavailableFeature feedback" in {
    val ctrl    = makeController()
    val sidebar = onFx { makeSidebar(ctrl) }
    onFx { buttons(importVBox(sidebar))(1).fire() }
    ctrl.currentState.feedback should matchPattern {
      case Some(SidebarFeedback.Failure(_, _, FailureCategory.UnavailableFeature)) =>
    }
  }

  "NotationSidebar Export FEN button" should "dispatch FenExport and populate outputText" in {
    val ctrl    = makeController()
    val sidebar = onFx { makeSidebar(ctrl) }
    onFx { buttons(exportVBox(sidebar))(0).fire() }
    ctrl.currentState.outputText shouldBe Some(InitialFen)
  }

  "NotationSidebar Export PGN button" should "dispatch PgnExport and produce UnavailableFeature feedback" in {
    val ctrl    = makeController()
    val sidebar = onFx { makeSidebar(ctrl) }
    onFx { buttons(exportVBox(sidebar))(1).fire() }
    ctrl.currentState.feedback should matchPattern {
      case Some(SidebarFeedback.Failure(_, _, FailureCategory.UnavailableFeature)) =>
    }
  }

  // ── Custom descriptors ───────────────────────────────────────────────────────

  "NotationSidebar with custom descriptors" should "render only the provided descriptors" in {
    val ctrl    = makeController()
    val sidebar = onFx {
      makeSidebar(ctrl, Seq(
        NotationActionDescriptor(NotationActionId.FenImport, "Import FEN", ActionKind.Import)
      ))
    }
    onFx {
      buttons(importVBox(sidebar))       should have size 1
      buttons(importVBox(sidebar))(0).getText shouldBe "Import FEN"
      buttons(exportVBox(sidebar))       should have size 0
    }
  }

  it should "use the custom label text on the button" in {
    val ctrl = makeController()
    val sidebar = onFx {
      makeSidebar(ctrl, Seq(
        NotationActionDescriptor(NotationActionId.FenExport, "Save as FEN", ActionKind.Export)
      ))
    }
    onFx { buttons(exportVBox(sidebar))(0).getText shouldBe "Save as FEN" }
  }

  // ── refresh: output text ─────────────────────────────────────────────────────

  "NotationSidebar.refresh" should "show output text and make outputArea visible" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    onFx { sidebar.refresh(NotationSidebarState(outputText = Some("fen-text"))) }
    onFx {
      outputArea(sidebar).getText      shouldBe "fen-text"
      outputArea(sidebar).isVisible    shouldBe true
    }
  }

  it should "clear outputArea and hide it when outputText is None" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    onFx { sidebar.refresh(NotationSidebarState(outputText = Some("fen-text"))) }
    onFx { sidebar.refresh(NotationSidebarState(outputText = None)) }
    onFx {
      outputArea(sidebar).getText   shouldBe ""
      outputArea(sidebar).isVisible shouldBe false
    }
  }

  // ── refresh: feedback ────────────────────────────────────────────────────────

  it should "show green text for Success feedback" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    onFx { sidebar.refresh(NotationSidebarState(feedback = Some(SidebarFeedback.Success("All good.")))) }
    onFx {
      feedbackLabel(sidebar).getText      shouldBe "All good."
      feedbackLabel(sidebar).isVisible    shouldBe true
      feedbackLabel(sidebar).getStyle     should include("7ec77e")
    }
  }

  it should "show red text for Failure feedback without details" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    onFx {
      sidebar.refresh(NotationSidebarState(feedback = Some(
        SidebarFeedback.Failure("Something went wrong.", None, FailureCategory.InvalidInput)
      )))
    }
    onFx {
      feedbackLabel(sidebar).getText   shouldBe "Something went wrong."
      feedbackLabel(sidebar).isVisible shouldBe true
      feedbackLabel(sidebar).getStyle  should include("e07070")
    }
  }

  it should "append details below the message for Failure feedback with details" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    onFx {
      sidebar.refresh(NotationSidebarState(feedback = Some(
        SidebarFeedback.Failure("Parse error.", Some("Line 1, column 5"), FailureCategory.InvalidInput)
      )))
    }
    onFx { feedbackLabel(sidebar).getText shouldBe "Parse error.\nLine 1, column 5" }
  }

  it should "hide the feedback label when feedback is None" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    onFx { sidebar.refresh(NotationSidebarState(feedback = Some(SidebarFeedback.Success("shown")))) }
    onFx { sidebar.refresh(NotationSidebarState(feedback = None)) }
    onFx {
      feedbackLabel(sidebar).getText   shouldBe ""
      feedbackLabel(sidebar).isVisible shouldBe false
    }
  }

  // ── refresh: warnings ────────────────────────────────────────────────────────

  it should "show one warning label per warning and make warningsBox visible" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    val warnings = List(
      GuiNotationWarning("Missing field",    GuiWarningCategory.Informational),
      GuiNotationWarning("Data was trimmed", GuiWarningCategory.DataLoss)
    )
    onFx { sidebar.refresh(NotationSidebarState(warnings = warnings)) }
    onFx {
      warningsVBox(sidebar).isVisible                     shouldBe true
      warningsVBox(sidebar).getChildren should have size 2
    }
  }

  it should "include the warning message in each warning label" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    val warnings = List(GuiNotationWarning("halfmove clock ignored", GuiWarningCategory.Informational))
    onFx { sidebar.refresh(NotationSidebarState(warnings = warnings)) }
    onFx {
      val lbl = warningsVBox(sidebar).getChildren.get(0).asInstanceOf[JLabel]
      lbl.getText should include("halfmove clock ignored")
    }
  }

  it should "hide the warningsBox and clear children when warnings are empty" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    val warnings = List(GuiNotationWarning("some warning", GuiWarningCategory.DataLoss))
    onFx { sidebar.refresh(NotationSidebarState(warnings = warnings)) }
    onFx { sidebar.refresh(NotationSidebarState(warnings = Nil)) }
    onFx {
      warningsVBox(sidebar).isVisible                     shouldBe false
      warningsVBox(sidebar).getChildren should have size 0
    }
  }

  it should "replace prior warnings on a second refresh call" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    onFx { sidebar.refresh(NotationSidebarState(warnings = List(
      GuiNotationWarning("w1", GuiWarningCategory.Informational),
      GuiNotationWarning("w2", GuiWarningCategory.Informational)
    ))) }
    onFx { sidebar.refresh(NotationSidebarState(warnings = List(
      GuiNotationWarning("w3", GuiWarningCategory.Normalization)
    ))) }
    onFx {
      warningsVBox(sidebar).getChildren should have size 1
      warningsVBox(sidebar).getChildren.get(0).asInstanceOf[JLabel].getText should include("w3")
    }
  }

  // ── refresh: combined state ──────────────────────────────────────────────────

  it should "correctly reflect a full ImportSuccess state (outputText=None, feedback=success, warnings)" in {
    val sidebar  = onFx { makeSidebar(makeController()) }
    val warnings = List(GuiNotationWarning("normalised ep", GuiWarningCategory.Normalization))
    onFx {
      sidebar.refresh(NotationSidebarState(
        outputText = None,
        feedback   = Some(SidebarFeedback.Success("Position imported successfully.")),
        warnings   = warnings
      ))
    }
    onFx {
      outputArea(sidebar).isVisible    shouldBe false
      feedbackLabel(sidebar).getText   shouldBe "Position imported successfully."
      feedbackLabel(sidebar).isVisible shouldBe true
      warningsVBox(sidebar).isVisible  shouldBe true
      warningsVBox(sidebar).getChildren should have size 1
    }
  }

  it should "correctly reflect a full ExportSuccess state (outputText=Some, feedback=None, no warnings)" in {
    val sidebar = onFx { makeSidebar(makeController()) }
    onFx {
      sidebar.refresh(NotationSidebarState(
        outputText = Some(InitialFen),
        feedback   = None,
        warnings   = Nil
      ))
    }
    onFx {
      outputArea(sidebar).getText      shouldBe InitialFen
      outputArea(sidebar).isVisible    shouldBe true
      feedbackLabel(sidebar).isVisible shouldBe false
      warningsVBox(sidebar).isVisible  shouldBe false
    }
  }
