package chess.adapter.gui.viewmodel

import chess.domain.model.{Move, PieceType}
import chess.domain.state.GameState

/** Pure mapper: derives a [[MoveHistoryViewModel]] from a [[GameState]].
  *
  * Pairs consecutive half-moves from [[GameState.moveHistory]] into rows. The first half-move of
  * each pair occupies the white column; the second occupies the black column. When the history has
  * an odd number of half-moves, the last row's black column is [[None]].
  *
  * Move notation format: coordinate notation "e2-e4", with promotion suffix "=Q" / "=R" / "=B" /
  * "=N" when applicable.
  */
object MoveHistoryViewModelMapper:

  def map(state: GameState): MoveHistoryViewModel =
    val moves = state.moveHistory.toIndexedSeq
    val rows = (0 until moves.length by 2).toVector.zipWithIndex.map { case (i, rowIdx) =>
      MoveHistoryRowViewModel(
        moveNumber = rowIdx + 1,
        whiteMove = formatMove(moves(i)),
        blackMove = if i + 1 < moves.length then Some(formatMove(moves(i + 1))) else None
      )
    }
    MoveHistoryViewModel(rows)

  private def formatMove(move: Move): String =
    val promotion = move.promotion.map(pt => s"=${promotionChar(pt)}").getOrElse("")
    s"${move.from}-${move.to}$promotion"

  private def promotionChar(pt: PieceType): String = pt match
    case PieceType.Queen  => "Q"
    case PieceType.Rook   => "R"
    case PieceType.Bishop => "B"
    case PieceType.Knight => "N"
    case _                => "?"
