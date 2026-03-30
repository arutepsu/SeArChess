// $COVERAGE-OFF$
package chess.adapter.gui.render

import chess.adapter.gui.assets.{PieceNodeFactory, PieceVisualId, VisualState}
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
   *                     it is instead drawn by the animation overlay. */
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
      val col        = BoardProjection.toScreenCol(sv.position)
      val row        = BoardProjection.toScreenRow(sv.position)
      val suppressed = suppressPos.contains(sv.position)
      grid.add(buildSquare(sv, onAction, factory, suppressed), col, row)

  // ── Square construction ─────────────────────────────────────────────────────

  private def buildSquare(sv: SquareViewModel, onAction: InputAction => Unit, factory: PieceNodeFactory, suppressed: Boolean = false): StackPane =
    val isLight = (sv.position.file + sv.position.rank) % 2 != 0
    val bgColor = squareColor(isLight, sv.isSelected, sv.isLegalTarget)

    val bg = new Rectangle:
      width  = SquareSize
      height = SquareSize
      fill   = bgColor

    val pane = new StackPane:
      alignment  = Pos.Center
      prefWidth  = SquareSize
      prefHeight = SquareSize
      onMouseClicked = _ => onAction(InputAction.SquareClicked(sv.position))

    pane.children.add(bg)

    // Legal-target indicator: dot for empty squares, ring for capture squares
    if sv.isLegalTarget then
      if sv.piece.isEmpty then
        val dot = new Circle:
          radius = SquareSize * 0.18
          fill   = FxColor.web("#00000033")
        pane.children.add(dot)
      else
        val ring = new Circle:
          radius      = SquareSize * 0.46
          fill        = FxColor.Transparent
          stroke      = FxColor.web("#00000055")
          strokeWidth = SquareSize * 0.08
        pane.children.add(ring)

    // Piece visual — skipped when suppressed (drawn by the animation overlay instead)
    if !suppressed then sv.piece.foreach { (color, pieceType) =>
      pane.children.add(factory.content(PieceVisualId(color, pieceType, VisualState.Idle), SquareSize,
        flipX = PieceFacingPolicy.flipX(color)))
    }

    pane

  private def squareColor(isLight: Boolean, selected: Boolean, target: Boolean): FxColor =
    if selected then if isLight then SelectedLight else SelectedDark
    else if target && !isLight then TargetDark
    else if target then TargetLight
    else if isLight then LightSquare
    else DarkSquare
