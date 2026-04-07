package chess.adapter.gui.viewmodel

/** GUI-facing view model for the entire move history display.
 *
 *  Rows are ordered oldest-first: index 0 = move number 1.
 *  [[latestRowIndex]] points to the last row so the panel can highlight it;
 *  it is derived from row count and carries no independent state.
 */
final case class MoveHistoryViewModel(rows: Vector[MoveHistoryRowViewModel]):

  def isEmpty: Boolean = rows.isEmpty

  /** Index of the most recent row, or [[None]] when history is empty. */
  def latestRowIndex: Option[Int] = if rows.nonEmpty then Some(rows.size - 1) else None
