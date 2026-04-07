package chess.adapter.gui.viewmodel

/** A single row in the move history display.
 *
 *  Each row groups one full move number:
 *  White's ply is always present; Black's ply is absent when the game ended
 *  after White's move or when a game loaded from a FEN has an odd ply count.
 */
final case class MoveHistoryRowViewModel(
    moveNumber: Int,
    whiteMove:  String,
    blackMove:  Option[String]
)
