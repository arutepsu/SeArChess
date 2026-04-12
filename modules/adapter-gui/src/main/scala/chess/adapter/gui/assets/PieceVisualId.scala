package chess.adapter.gui.assets

import chess.domain.model.{Color, PieceType}

/** Stable identity for a piece visual asset.
 *
 *  Combines the three axes that determine which visual resource to use:
 *  piece colour, piece type, and the current [[VisualState]].
 *
 *  This is intentionally kept as pure data — no loading, no caching,
 *  no ScalaFX dependency.  [[VisualResolver]] maps instances of this
 *  type to [[VisualDescriptor]] metadata.
 *
 *  @param color     colour of the piece (White / Black)
 *  @param pieceType type of the piece (King, Queen, …)
 *  @param state     the visual state the piece is currently in
 */
final case class PieceVisualId(
  color:     Color,
  pieceType: PieceType,
  state:     VisualState
)
