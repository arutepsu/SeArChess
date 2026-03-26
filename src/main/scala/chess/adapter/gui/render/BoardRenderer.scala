// $COVERAGE-OFF$
package chess.adapter.gui.render

import chess.adapter.gui.assets.PieceSymbol
import chess.adapter.gui.input.InputAction
import chess.adapter.gui.viewmodel.{GameViewModel, SquareViewModel}
import chess.domain.model.{Color, Position}
import scalafx.geometry.Pos
import scalafx.scene.layout.{GridPane, StackPane}
import scalafx.scene.paint.{Color as FxColor}
import scalafx.scene.shape.{Circle, Rectangle}
import scalafx.scene.text.{Font, Text}

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

  def create(vm: GameViewModel, onAction: InputAction => Unit): GridPane =
    val grid = new GridPane
    grid.hgap = 0
    grid.vgap = 0
    populate(grid, vm, onAction, None)
    grid

  /** @param suppressPos when [[Some]], the piece on that square is not rendered —
   *                     it is instead drawn by the animation overlay. */
  def update(
      grid:        GridPane,
      vm:          GameViewModel,
      onAction:    InputAction => Unit,
      suppressPos: Option[Position] = None
  ): Unit =
    grid.children.clear()
    populate(grid, vm, onAction, suppressPos)

  private def populate(
      grid:        GridPane,
      vm:          GameViewModel,
      onAction:    InputAction => Unit,
      suppressPos: Option[Position]
  ): Unit =
    for sv <- vm.squares do
      val col        = sv.position.file
      val row        = 7 - sv.position.rank    // flip: rank 7 at top, rank 0 at bottom
      val suppressed = suppressPos.contains(sv.position)
      grid.add(buildSquare(sv, onAction, suppressed), col, row)

  // ── Square construction ─────────────────────────────────────────────────────

  private def buildSquare(sv: SquareViewModel, onAction: InputAction => Unit, suppressed: Boolean = false): StackPane =
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

    // Piece glyph — skipped when suppressed (drawn by the animation overlay instead)
    if !suppressed then sv.piece.foreach { (color, pieceType) =>
      val glyph = new Text:
        text      = PieceSymbol.symbol(color, pieceType)
        font      = Font("Segoe UI Symbol", SquareSize * 0.62)
        fill      = if color == Color.White then FxColor.web("#fffffe") else FxColor.web("#1a1a1a")
        stroke    = if color == Color.White then FxColor.web("#333333") else FxColor.web("#cccccc")
        strokeWidth = 0.6
      pane.children.add(glyph)
    }

    pane

  private def squareColor(isLight: Boolean, selected: Boolean, target: Boolean): FxColor =
    if selected then if isLight then SelectedLight else SelectedDark
    else if target && !isLight then TargetDark
    else if target then TargetLight
    else if isLight then LightSquare
    else DarkSquare
