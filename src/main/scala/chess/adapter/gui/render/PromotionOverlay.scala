// $COVERAGE-OFF$
package chess.adapter.gui.render

import chess.adapter.gui.assets.{PieceVisualId, VisualResolver, VisualState}
import chess.adapter.gui.input.InputAction
import chess.adapter.gui.viewmodel.PromotionViewModel
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.control.Button
import scalafx.scene.layout.{HBox, VBox}
import scalafx.scene.paint.Color as FxColor
import scalafx.scene.text.{Font, Text}

/** Builds the promotion-chooser overlay panel.
 *
 *  Displayed centred over the board when GuiState is AwaitingPromotion.
 *  Fires [[InputAction.PromotionPieceChosen]] when the user clicks a piece button.
 */
object PromotionOverlay:

  def create(vm: PromotionViewModel, dispatch: InputAction => Unit): VBox =
    val buttons = vm.choices.map { pt =>
      val descriptor = VisualResolver.resolve(PieceVisualId(vm.promotingColor, pt, VisualState.Idle))
      val btn = new Button:
        text     = descriptor.fallbackSymbol
        font     = Font("Segoe UI Symbol", 40)
        prefWidth  = 76
        prefHeight = 76
        style    = "-fx-background-color: #ffffffdd; -fx-border-color: #888; -fx-border-radius: 4; -fx-background-radius: 4;"
        onAction = _ => dispatch(InputAction.PromotionPieceChosen(pt))
      btn
    }

    val row = new HBox:
      spacing  = 8
      children = buttons

    val title = new Text:
      text  = "Promote pawn — choose a piece"
      font  = Font("System", 15)
      fill  = FxColor.White

    new VBox:
      alignment = Pos.Center
      spacing   = 12
      padding   = Insets(20)
      style     = "-fx-background-color: rgba(0,0,0,0.72); -fx-background-radius: 10;"
      children  = Seq(title, row)
