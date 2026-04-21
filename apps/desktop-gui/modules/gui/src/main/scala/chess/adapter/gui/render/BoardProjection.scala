// $COVERAGE-OFF$
package chess.adapter.gui.render

import chess.domain.model.Position

/** Single source of truth for mapping board [[Position]] values to screen layout coordinates in the
  * GUI adapter.
  *
  * Encodes a **90° clockwise rotation** relative to the conventional rank-up orientation:
  *   - Ranks run **left → right** (rank 0 = left edge, rank 7 = right edge).
  *   - Files run **top → bottom** (file 7 = top edge, file 0 = bottom edge).
  *
  * {{{
  *       rank 0  1  2  3  4  5  6  7
  *  f 7 ┌──┬──┬──┬──┬──┬──┬──┬──┐
  *  i 6 │  │  │  │  │  │  │  │  │
  *  l 5 ├──┤  …                  │
  *  e 4 │  │                     │
  *    3 │  │                     │
  *    2 │  │                     │
  *    1 │  │                     │
  *    0 └──┴──┴──┴──┴──┴──┴──┴──┘
  * }}}
  *
  * White's back rank (rank 0) is on the left; Black's (rank 7) is on the right.
  *
  * No ScalaFX dependencies; no mutable state. Used by [[BoardRenderer]] for grid placement and by
  * [[chess.adapter.gui.animation.AnimationPresentationMapper]] for pixel-coordinate computation.
  */
object BoardProjection:

  /** Grid column for [[pos]] (0 = left edge of the board). */
  def toScreenCol(pos: Position): Int = pos.rank

  /** Grid row for [[pos]] (0 = top edge of the board). */
  def toScreenRow(pos: Position): Int = 7 - pos.file

  /** Board-local pixel x of the top-left corner of the square at [[pos]].
    *
    * @param squareSize
    *   side length of one board square in pixels
    */
  def toPixelX(pos: Position, squareSize: Double): Double = pos.rank * squareSize

  /** Board-local pixel y of the top-left corner of the square at [[pos]].
    *
    * @param squareSize
    *   side length of one board square in pixels
    */
  def toPixelY(pos: Position, squareSize: Double): Double = (7 - pos.file) * squareSize
