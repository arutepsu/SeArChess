package chess.adapter.gui.viewmodel

import chess.domain.model.{Color, PieceType}

/** Data required to render the promotion chooser overlay.
 *
 *  @param promotingColor the color of the promoting pawn (determines which glyphs to display)
 *  @param choices        the selectable piece types, always Queen / Rook / Bishop / Knight
 */
final case class PromotionViewModel(
  promotingColor: Color,
  choices:        List[PieceType]
)

object PromotionViewModel:
  val standardChoices: List[PieceType] =
    List(PieceType.Queen, PieceType.Rook, PieceType.Bishop, PieceType.Knight)
