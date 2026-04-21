// $COVERAGE-OFF$
package chess.adapter.gui.render

import chess.adapter.gui.viewmodel.{MoveHistoryRowViewModel, MoveHistoryViewModel}
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.control.{Label, ScrollPane}
import scalafx.scene.layout.{HBox, Priority, VBox}
import scalafx.scene.text.Font

/** ScalaFX widget that renders a [[MoveHistoryViewModel]] as a scrollable list.
  *
  * Each row shows: move number, white half-move, black half-move (or placeholder).
  *
  * Responsibilities:
  *   - render move rows from a view model via [[refresh]]
  *   - show a placeholder when no moves have been played
  *
  * Does NOT own or derive game state; updated only via [[refresh]].
  */
class MoveHistoryPanel:

  private def makeRow(row: MoveHistoryRowViewModel): HBox =
    val numLabel = new Label(s"${row.moveNumber}."):
      minWidth = 28
      style = "-fx-font-size: 12; -fx-text-fill: #999999;"

    val whiteLabel = new Label(row.whiteMove):
      HBox.setHgrow(this, Priority.Always)
      style = "-fx-font-size: 12; -fx-text-fill: #e8e6e3;"

    val blackLabel = new Label(row.blackMove.getOrElse("—")):
      HBox.setHgrow(this, Priority.Always)
      style = "-fx-font-size: 12; -fx-text-fill: #e8e6e3;"
      opacity = if row.blackMove.isDefined then 1.0 else 0.3

    new HBox(4, numLabel, whiteLabel, blackLabel):
      alignment = Pos.CenterLeft
      padding = Insets(1, 4, 1, 4)

  private val emptyLabel = new Label("No moves yet."):
    style = "-fx-font-size: 12; -fx-text-fill: #666666; -fx-padding: 4 4 4 4;"

  private val rowsBox = new VBox:
    spacing = 0
    children = Seq(emptyLabel)

  private val scrollPane = new ScrollPane:
    content = rowsBox
    fitToWidth = true
    prefHeight = 180
    style =
      "-fx-background: transparent; -fx-background-color: transparent; -fx-border-color: transparent;"

  private val header = new Label("History"):
    font = Font("System Bold", 13)
    style = "-fx-text-fill: #cccccc; -fx-padding: 6 0 2 0;"

  val root: VBox = new VBox:
    spacing = 4
    padding = Insets(0, 12, 12, 12)
    children = Seq(header, scrollPane)

  /** Replace the displayed rows with those from `vm`. */
  def refresh(vm: MoveHistoryViewModel): Unit =
    rowsBox.children.clear()
    if vm.rows.isEmpty then rowsBox.children.add(emptyLabel)
    else vm.rows.foreach(row => rowsBox.children.add(makeRow(row)))
