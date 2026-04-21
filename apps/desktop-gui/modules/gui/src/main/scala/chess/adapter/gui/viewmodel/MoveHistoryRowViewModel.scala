package chess.adapter.gui.viewmodel

/** A single display row in the move history sidebar.
  *
  * Each row covers one full move: white's half-move is always present; black's half-move is
  * [[None]] when the game ends on an odd half-move.
  *
  * @param moveNumber
  *   1-based full-move number
  * @param whiteMove
  *   coordinate notation for white's half-move (e.g. "e2-e4")
  * @param blackMove
  *   coordinate notation for black's half-move, if played
  */
final case class MoveHistoryRowViewModel(
    moveNumber: Int,
    whiteMove: String,
    blackMove: Option[String]
)
