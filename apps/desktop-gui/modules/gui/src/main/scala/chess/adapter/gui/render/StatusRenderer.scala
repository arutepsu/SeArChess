// $COVERAGE-OFF$
package chess.adapter.gui.render

import chess.adapter.gui.viewmodel.GameViewModel
import scalafx.scene.control.Label
import scalafx.scene.text.Font

/** Renders the status-bar label from a [[GameViewModel]]. */
object StatusRenderer:

  def create(vm: GameViewModel): Label =
    val lbl = new Label:
      font = Font("System Bold", 16)
      style = "-fx-padding: 8 12 8 12;"
    update(lbl, vm)
    lbl

  def update(label: Label, vm: GameViewModel): Unit =
    label.text = vm.statusText
