// $COVERAGE-OFF$
package chess.adapter.gui.render

import chess.adapter.gui.assets.{PieceNodeFactory, PieceVisualId, VisualState}
import chess.adapter.gui.viewmodel.GameViewModel
import chess.domain.model.Position
import scalafx.scene.layout.Pane

/** Renders all non-animated board pieces on a separate mouse-transparent overlay.
 *
 *  This decouples visual sprite size from board hit detection.  Board clicks are
 *  handled entirely by the background grid; pieces are pure visuals here.
 */
object StaticPieceOverlayRenderer:

  def create(
      vm: GameViewModel,
      factory: PieceNodeFactory,
      suppressPos: Option[Position] = None
  ): Pane =
    val pane = new Pane:
      mouseTransparent = true
      pickOnBounds = false
      prefWidth = BoardRenderer.SquareSize * 8
      prefHeight = BoardRenderer.SquareSize * 8

    update(pane, vm, factory, suppressPos)
    pane

  def update(
      pane: Pane,
      vm: GameViewModel,
      factory: PieceNodeFactory,
      suppressPos: Option[Position] = None
  ): Unit =
    pane.children.clear()

    for
      sv <- vm.squares
      if !suppressPos.contains(sv.position)
      (color, pieceType) <- sv.piece
    do
      val col = BoardProjection.toScreenCol(sv.position)
      val row = BoardProjection.toScreenRow(sv.position)

      val x = col * BoardRenderer.SquareSize
      val y = row * BoardRenderer.SquareSize

      val pieceNode =
        factory.positioned(
          id = PieceVisualId(color, pieceType, VisualState.Idle),
          x = x,
          y = y,
          squareSize = BoardRenderer.SquareSize,
          flipX = PieceFacingPolicy.flipX(color)
        )

      pieceNode.mouseTransparent = true
      pane.children.add(pieceNode)