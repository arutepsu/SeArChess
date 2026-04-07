package chess.adapter.gui.viewmodel

/** View model for the move history sidebar.
 *
 *  @param rows  ordered list of full-move rows, first entry is move 1
 */
final case class MoveHistoryViewModel(rows: Vector[MoveHistoryRowViewModel])

object MoveHistoryViewModel:
  val empty: MoveHistoryViewModel = MoveHistoryViewModel(Vector.empty)
