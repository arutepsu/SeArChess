package chess.adapter.gui.viewmodel

import chess.domain.model.{Color, PieceType, Position}

/** Presentation data for a single board square.
 *
 *  @param position     the chess square this cell represents
 *  @param piece        the piece on this square, if any
 *  @param isSelected   this square holds the currently-selected piece
 *  @param isLegalTarget this square is a legal destination for the selected piece
 */
final case class SquareViewModel(
  position:      Position,
  piece:         Option[(Color, PieceType)],
  isSelected:    Boolean,
  isLegalTarget: Boolean
)
