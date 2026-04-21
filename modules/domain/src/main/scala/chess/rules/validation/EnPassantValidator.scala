package chess.domain.rules.validation

import chess.domain.error.DomainError
import chess.domain.model.*
import chess.domain.state.EnPassantState

/** Validates whether a move is a legal en passant capture.
  *
  * Responsibilities:
  *   - identify moves that should be routed through the en passant branch
  *   - check that the diagonal direction is correct for the capturing color
  *   - verify the target square is empty (en passant always captures to empty)
  *   - verify the capturable pawn is present at the expected square
  *
  * King safety is enforced by MoveApplier after applying the board change.
  */
object EnPassantValidator:

  /** True if `move` should be handled as an en passant capture.
    *
    * Routes to the en passant branch when:
    *   - en passant state is active
    *   - the destination equals the en passant target square
    *   - the moving piece is a pawn of the capturing color (opposite of pawnColor)
    *   - the target square is empty (en passant always moves to an empty square)
    */
  def isEnPassantMove(board: Board, move: Move, enPassantState: Option[EnPassantState]): Boolean =
    enPassantState match
      case Some(ep) =>
        move.to == ep.targetSquare &&
        board.pieceAt(move.to).isEmpty &&
        board
          .pieceAt(move.from)
          .exists(p => p.pieceType == PieceType.Pawn && p.color != ep.pawnColor)
      case None => false

  /** Validate a move already identified as a candidate en passant capture.
    *
    * Checks:
    *   - the capturing pawn advances exactly one file and one rank in the correct forward direction
    *     for its color
    *   - the target square is empty
    *   - the capturable pawn is present and of the expected color at capturablePawnSquare
    */
  def validate(board: Board, move: Move, enPassant: EnPassantState): Either[DomainError, Unit] =
    val capturer = enPassant.pawnColor.opposite
    val forwardRankDelta = if capturer == Color.White then 1 else -1
    val valid =
      math.abs(move.from.file - move.to.file) == 1 &&
        move.to.rank - move.from.rank == forwardRankDelta &&
        board.pieceAt(move.to).isEmpty &&
        board
          .pieceAt(enPassant.capturablePawnSquare)
          .contains(Piece(enPassant.pawnColor, PieceType.Pawn))
    Either.cond(valid, (), DomainError.InvalidEnPassant)
