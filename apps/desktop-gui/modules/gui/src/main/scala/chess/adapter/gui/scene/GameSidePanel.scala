// $COVERAGE-OFF$
package chess.adapter.gui.scene

import chess.adapter.gui.notation.NotationSidebar
import chess.adapter.gui.render.MoveHistoryPanel
import chess.adapter.gui.viewmodel.MoveHistoryViewModel
import scalafx.scene.control.Separator
import scalafx.scene.layout.{Priority, VBox}

/** Composed right-side panel hosting the notation section and move history section.
  *
  * This is a thin layout compositor — it owns no application state and makes no decisions. The
  * notation sidebar retains its own controller; the history panel is refreshed by the scene via
  * [[refreshHistory]].
  *
  * Layout (top to bottom):
  *   1. Notation section ([[NotationSidebar.root]]) 2. Horizontal separator 3. History section
  *      ([[MoveHistoryPanel.root]])
  *
  * @param notationSidebar
  *   existing notation sidebar; its root is mounted directly
  * @param historyPanel
  *   move history panel; updated via [[refreshHistory]]
  */
class GameSidePanel(
    notationSidebar: NotationSidebar,
    historyPanel: MoveHistoryPanel
):

  private val separator = new Separator:
    style = "-fx-background-color: #444444; -fx-border-color: transparent;"

  val root: VBox = new VBox:
    prefWidth = 220
    style = "-fx-background-color: #2a2623;"
    VBox.setVgrow(historyPanel.root, Priority.Always)
    children = Seq(notationSidebar.root, separator, historyPanel.root)

  /** Push a new [[MoveHistoryViewModel]] to the history section.
    *
    * Called by the scene on every refresh; the VM is derived from the controller's current game
    * state, never by this panel.
    */
  def refreshHistory(vm: MoveHistoryViewModel): Unit =
    historyPanel.refresh(vm)
