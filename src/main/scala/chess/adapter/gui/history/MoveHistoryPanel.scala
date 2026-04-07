// $COVERAGE-OFF$
package chess.adapter.gui.history

import chess.adapter.gui.viewmodel.MoveHistoryViewModel
import scalafx.geometry.Insets
import scalafx.scene.control.{Label, ScrollPane}
import scalafx.scene.layout.{HBox, VBox}
import scalafx.scene.text.Font

/** Read-only move history panel widget.
 *
 *  Renders a [[MoveHistoryViewModel]] as a scrollable list of numbered move rows.
 *  Each row shows: move number · White's move text · Black's move text (if present).
 *  The most recent row is rendered in bold/white; earlier rows are subdued.
 *
 *  Empty history is shown as an explanatory placeholder message.
 *
 *  Does NOT:
 *  - own [[chess.domain.state.GameState]]
 *  - communicate with [[chess.adapter.gui.controller.GameController]]
 *  - implement replay or navigation controls
 */
class MoveHistoryPanel:

  private val emptyLabel = new Label("No moves yet."):
    style = "-fx-text-fill: #999999; -fx-font-style: italic; -fx-padding: 8 4 4 4;"

  private val rowsBox = new VBox:
    spacing = 1

  private val scroll = new ScrollPane:
    fitToWidth = true
    content    = emptyLabel
    style      = "-fx-background: transparent; -fx-background-color: transparent;"

  private def sectionHeader(text: String): Label =
    new Label(text):
      font  = Font("System Bold", 13)
      style = "-fx-text-fill: #cccccc; -fx-padding: 6 0 2 0;"

  val root: VBox = new VBox:
    spacing   = 4
    padding   = Insets(12)
    prefWidth = 220
    style     = "-fx-background-color: #2a2623;"
    children  = Seq(sectionHeader("Move History"), scroll)

  /** Refresh the panel from a new view model. */
  def refresh(vm: MoveHistoryViewModel): Unit =
    if vm.isEmpty then
      scroll.content = emptyLabel
    else
      rowsBox.children.clear()
      val numStyle    = "-fx-text-fill: #888888; -fx-font-size: 11;" +
                        " -fx-min-width: 28; -fx-pref-width: 28;"
      val moveStyle   = "-fx-text-fill: #dddddd; -fx-font-size: 12;" +
                        " -fx-min-width: 72; -fx-pref-width: 72;"
      val latestStyle = "-fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-font-size: 12;" +
                        " -fx-min-width: 72; -fx-pref-width: 72;"
      vm.rows.zipWithIndex.foreach { case (row, idx) =>
        val textStyle = if vm.latestRowIndex.contains(idx) then latestStyle else moveStyle
        val hbox = new HBox:
          spacing  = 4
          padding  = Insets(1, 4, 1, 4)
          children = Seq(
            new Label(s"${row.moveNumber}.") { style = numStyle },
            new Label(row.whiteMove)          { style = textStyle },
            new Label(row.blackMove.getOrElse("")) { style = textStyle }
          )
        rowsBox.children.add(hbox)
      }
      scroll.content = rowsBox
