package chess.adapter.gui.render

import chess.domain.model.Color

/** Presentation-layer policy for horizontal sprite facing on the static board.
 *
 *  Sprites are authored facing right.  With the board in horizontal orientation
 *  (White on the left, Black on the right) Black pieces must be mirrored so
 *  they face their opponent.
 *
 *  No ScalaFX dependencies; no mutable state.
 */
object PieceFacingPolicy:

  /** Returns `true` when the sprite for [[color]] should be horizontally flipped. */
  def flipX(color: Color): Boolean = color == Color.Black
