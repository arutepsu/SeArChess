package chess.adapter.gui.viewmodel

import chess.domain.model.PieceType

/** Root presentation model consumed by the renderer.
  *
  * The renderer reads only from this; it never touches domain or application objects.
  *
  * @param squares
  *   all 64 squares in rank-then-file order (rank 0..7, file 0..7)
  * @param guiState
  *   current state-machine node
  * @param statusText
  *   human-readable status line
  * @param promotion
  *   Some when the promotion overlay should be visible
  */
final case class GameViewModel(
    squares: IndexedSeq[SquareViewModel],
    guiState: GuiState,
    statusText: String,
    promotion: Option[PromotionViewModel]
)
