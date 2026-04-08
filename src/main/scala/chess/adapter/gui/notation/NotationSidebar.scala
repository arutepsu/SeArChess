// $COVERAGE-OFF$
package chess.adapter.gui.notation

import scalafx.geometry.{Insets, Pos}
import scalafx.scene.control.{Button, Label, TextArea}
import scalafx.scene.layout.VBox
import scalafx.scene.text.Font

/** ScalaFX notation sidebar widget.
 *
 *  Builds the visual component tree from a list of [[NotationActionDescriptor]]s
 *  and wires user interactions to [[NotationSidebarController.handle]].
 *  Widget state is reflected from [[NotationSidebarState]] via [[refresh]].
 *
 *  Layout:
 *  - Import section: input text area + one button per descriptor with [[ActionKind.Import]]
 *  - Export section: one button per descriptor with [[ActionKind.Export]] + output text area
 *  - Feedback / warning area beneath both sections
 *
 *  Adding a new notation action requires only registering a new
 *  [[NotationActionDescriptor]] — no change to this widget is needed.
 *
 *  Responsibilities:
 *  - render input area and action buttons from descriptors
 *  - render feedback / warning messages
 *  - dispatch [[SidebarAction.NotationActionRequested]] to the controller
 *
 *  Does NOT:
 *  - own application [[chess.domain.state.GameState]]
 *  - call notation-layer APIs directly
 *  - mutate the active scene or controller
 *
 *  @param controller   the controller that owns sidebar state and routing
 *  @param descriptors  the notation actions to render, in display order;
 *                      defaults to [[NotationActionDescriptor.defaults]]
 */
class NotationSidebar(
    controller:  NotationSidebarController,
    descriptors: Seq[NotationActionDescriptor] = NotationActionDescriptor.defaults
):

  // ── Input area ──────────────────────────────────────────────────────────────

  private val inputArea = new TextArea:
    promptText   = "Paste notation here (FEN or PGN)…"
    prefRowCount = 4
    wrapText     = true
    style        = "-fx-font-family: monospace; -fx-font-size: 12;"
    text.onChange { (_, _, newText) =>
      controller.handle(SidebarAction.InputTextChanged(newText))
    }

  // ── Action button rows (descriptor-driven) ──────────────────────────────────

  private def makeActionButton(descriptor: NotationActionDescriptor): Button =
    new Button(descriptor.label):
      prefWidth = Double.MaxValue
      maxWidth  = Double.MaxValue
      onAction  = _ => controller.handle(SidebarAction.NotationActionRequested(descriptor.id))

  private def makeActionColumn(kind: ActionKind): VBox =
    val buttons = descriptors.filter(_.kind == kind).map(makeActionButton)
    new VBox:
      spacing  = 4
      children = buttons

  private val importRow: VBox = makeActionColumn(ActionKind.Import)
  private val exportRow: VBox = makeActionColumn(ActionKind.Export)

  // ── Output area ─────────────────────────────────────────────────────────────

  private val outputArea = new TextArea:
    promptText   = "Export output will appear here…"
    prefRowCount = 4
    wrapText     = true
    editable     = false
    style        = "-fx-font-family: monospace; -fx-font-size: 12; -fx-opacity: 0.85;"

  // ── Feedback area ───────────────────────────────────────────────────────────

  private val feedbackLabel = new Label:
    wrapText = true
    maxWidth = Double.MaxValue
    style    = "-fx-font-size: 12;"
    visible  = false

  private val warningsBox = new VBox:
    spacing = 2
    visible = false

  // ── Section headers ─────────────────────────────────────────────────────────

  private def sectionHeader(text: String): Label =
    new Label(text):
      font  = Font("System Bold", 13)
      style = "-fx-text-fill: #cccccc; -fx-padding: 6 0 2 0;"

  // ── Root layout ─────────────────────────────────────────────────────────────

  val root: VBox = new VBox:
    spacing   = 8
    padding   = Insets(12)
    prefWidth = 220
    style     = "-fx-background-color: #2a2623;"
    children  = Seq(
      sectionHeader("Notation"),
      inputArea,
      importRow,
      sectionHeader("Export"),
      exportRow,
      outputArea,
      feedbackLabel,
      warningsBox
    )

  // ── Refresh ─────────────────────────────────────────────────────────────────

  /** Update the widget from a new [[NotationSidebarState]].
   *
   *  Called by the controller after every transition.
   */
  def refresh(state: NotationSidebarState): Unit =
    state.outputText match
      case Some(text) =>
        outputArea.text    = text
        outputArea.visible = true
      case None =>
        outputArea.text    = ""
        outputArea.visible = false

    state.feedback match
      case Some(SidebarFeedback.Success(msg)) =>
        feedbackLabel.text    = msg
        feedbackLabel.style   = "-fx-font-size: 12; -fx-text-fill: #7ec77e;"
        feedbackLabel.visible = true
      case Some(SidebarFeedback.Failure(msg, details, _)) =>
        val full = details.fold(msg)(d => s"$msg\n$d")
        feedbackLabel.text    = full
        feedbackLabel.style   = "-fx-font-size: 12; -fx-text-fill: #e07070;"
        feedbackLabel.visible = true
      case None =>
        feedbackLabel.text    = ""
        feedbackLabel.visible = false

    NotationSidebar.updateWarningsBox(warningsBox, state.warnings)


object NotationSidebar:
  // Workaround für Coverage: extrahiere das Warning-UI-Handling
  private[notation] def updateWarningsBox(warningsBox: scalafx.scene.layout.VBox, warnings: List[GuiNotationWarning]): Unit =
    if warnings.isEmpty then
      warningsBox.children.clear()
      warningsBox.visible = false
    else
      warningsBox.children.clear()
      warnings.foreach { w =>
        val lbl = new scalafx.scene.control.Label(s"⚠ ${w.message}"):
          wrapText = true
          maxWidth = Double.MaxValue
          style    = "-fx-font-size: 11; -fx-text-fill: #d4b44a;"
        warningsBox.children.add(lbl)
      }
      warningsBox.visible = true
