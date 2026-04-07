package chess.adapter.gui.viewmodel

import chess.domain.model.{Move, PieceType}
import chess.domain.state.GameState

/** Builds a [[MoveHistoryViewModel]] from the domain move history.
 *
 *  Accepts either a full [[GameState]] or a raw [[List[Move]]].
 *
 *  === Formatting policy ===
 *  - Normal move  → `e2-e4`      (from.toString + "-" + to.toString)
 *  - Promotion    → `e7-e8=Q`    (appends `=` + piece letter)
 *  - Castling stays in coordinate-style (`e1-g1`, `e1-c1`) as that is what
 *    the domain [[Move]] currently encodes; no SAN/PGN notation is applied.
 *
 *  === Grouping policy ===
 *  Plies are paired by index: (0,1) → row 1, (2,3) → row 2, …
 *  Ply 0 is always White's move.  When history has an odd ply count the last
 *  row has a white move only (blackMove = None).
 */
object MoveHistoryViewModelMapper:

  def from(state: GameState): MoveHistoryViewModel =
    from(state.moveHistory)

  def from(history: List[Move]): MoveHistoryViewModel =
    if history.isEmpty then MoveHistoryViewModel(Vector.empty)
    else
      val rows = history
        .grouped(2)
        .toVector
        .zipWithIndex
        .map { case (group, idx) =>
          MoveHistoryRowViewModel(
            moveNumber = idx + 1,
            whiteMove  = formatMove(group(0)),
            blackMove  = if group.size > 1 then Some(formatMove(group(1))) else None
          )
        }
      MoveHistoryViewModel(rows)

  private def formatMove(move: Move): String =
    val base = s"${move.from}-${move.to}"
    move.promotion match
      case None     => base
      case Some(pt) => s"$base=${pieceTypeLetter(pt)}"

  private def pieceTypeLetter(pt: PieceType): String = pt match
    case PieceType.Queen  => "Q"
    case PieceType.Rook   => "R"
    case PieceType.Bishop => "B"
    case PieceType.Knight => "N"
    case PieceType.King   => "K"
    case PieceType.Pawn   => "P"
