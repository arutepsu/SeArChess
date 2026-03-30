// $COVERAGE-OFF$
package chess.adapter.gui.render

import chess.adapter.gui.assets.PieceNodeFactory
import chess.adapter.gui.input.InputAction
import chess.adapter.gui.viewmodel.{GameViewModel, SquareViewModel}
import chess.domain.model.Position
import scalafx.geometry.Pos
import scalafx.scene.layout.{GridPane, StackPane}
import scalafx.scene.paint.{Color as FxColor}
import scalafx.scene.shape.{Circle, Rectangle}

/** Creates and updates the 8×8 board GridPane from a [[GameViewModel]].
 *
 *  Call [[create]] once; call [[update]] whenever the view model changes.
 */
object BoardRenderer:

  val SquareSize = 72.0

  private val LightSquare    = FxColor.web("#f0d9b5")
  private val DarkSquare     = FxColor.web("#b58863")
  private val SelectedLight  = FxColor.web("#f6f669")
  private val SelectedDark   = FxColor.web("#baca2b")
  private val TargetLight    = FxColor.web("#cdd16e")
  private val TargetDark     = FxColor.web("#aaa23a")

  def create(vm: GameViewModel, onAction: InputAction => Unit, factory: PieceNodeFactory): GridPane =
    val grid = new GridPane
    grid.hgap = 0
    grid.vgap = 0
    populate(grid, vm, onAction, factory, None)
    grid

  /** @param suppressPos when [[Some]], the piece on that square is not rendered —
   *                     kept for API compatibility with animation refresh flow.
   *                     The board layer itself no longer renders pieces.
   */
  def update(
      grid:        GridPane,
      vm:          GameViewModel,
      onAction:    InputAction => Unit,
      factory:     PieceNodeFactory,
      suppressPos: Option[Position] = None
  ): Unit =
    grid.children.clear()
    populate(grid, vm, onAction, factory, suppressPos)

  private def populate(
      grid:        GridPane,
      vm:          GameViewModel,
      onAction:    InputAction => Unit,
      factory:     PieceNodeFactory,
      suppressPos: Option[Position]
  ): Unit =
    for sv <- vm.squares do
      val col = BoardProjection.toScreenCol(sv.position)
      val row = BoardProjection.toScreenRow(sv.position)
      grid.add(buildSquare(sv, onAction), col, row)

  private def buildSquare(
      sv: SquareViewModel,
      onAction: InputAction => Unit
  ): StackPane =
    val isLight = (sv.position.file + sv.position.rank) % 2 != 0
    val bgColor = squareColor(isLight, sv.isSelected, sv.isLegalTarget)

    val bg = new Rectangle:
      width            = SquareSize
      height           = SquareSize
      fill             = bgColor
      mouseTransparent = false
      onMouseClicked   = _ => onAction(InputAction.SquareClicked(sv.position))

    val pane = new StackPane:
      alignment  = Pos.Center
      prefWidth  = SquareSize
      prefHeight = SquareSize

    pane.children.add(bg)

    if sv.isLegalTarget then
      if sv.piece.isEmpty then
        val dot = new Circle:
          radius           = SquareSize * 0.18
          fill             = FxColor.web("#00000033")
          mouseTransparent = true
        pane.children.add(dot)
      else
        val ring = new Circle:
          radius           = SquareSize * 0.46
          fill             = FxColor.Transparent
          stroke           = FxColor.web("#00000055")
          strokeWidth      = SquareSize * 0.08
          mouseTransparent = true
        pane.children.add(ring)

    pane

  private def squareColor(isLight: Boolean, selected: Boolean, target: Boolean): FxColor =
    if selected then if isLight then SelectedLight else SelectedDark
    else if target && !isLight then TargetDark
    else if target then TargetLight
    else if isLight then LightSquare
    else DarkSquare