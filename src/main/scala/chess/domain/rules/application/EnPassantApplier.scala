package chess.domain.rules.application

import chess.domain.model.{Board, Move}
import chess.domain.model.positionstate.EnPassantState

/** Applies a validated en passant capture by moving the capturing pawn
 *  and removing the captured pawn from its square.
 *
 *  Precondition: EnPassantValidator.validate has already succeeded.
 */
object EnPassantApplier:

  def applyEnPassant(board: Board, move: Move, enPassant: EnPassantState): Board =
    val capturingPawn = board.pieceAt(move.from).get  // guaranteed valid by EnPassantValidator
    board
      .remove(move.from)
      .place(move.to, capturingPawn)
      .remove(enPassant.capturablePawnSquare)
