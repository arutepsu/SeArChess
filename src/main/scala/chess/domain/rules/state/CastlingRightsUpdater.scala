package chess.domain.rules.state

import chess.domain.model.*
import chess.domain.model.positionstate.CastlingRights

/** Updates castling rights after any move.
 *
 *  A right is cleared when:
 *  - the king of that color moves (clears both rights for that color)
 *  - a rook moves from its original square (clears the corresponding right)
 *  - a rook is captured on its original square (clears the corresponding right)
 *
 *  All three checks are independent and applied in sequence.
 *  `boardBefore` is the board state before the move was applied.
 */
object CastlingRightsUpdater:

  private[state] def constPos(file: Int, rank: Int): Position =
    Position.from(file, rank).getOrElse(throw AssertionError(s"Invalid castling constant: file=$file rank=$rank"))

  private val whiteKingStart     = constPos(4, 0)  // e1
  private val blackKingStart     = constPos(4, 7)  // e8
  private val whiteKingSideRook  = constPos(7, 0)  // h1
  private val whiteQueenSideRook = constPos(0, 0)  // a1
  private val blackKingSideRook  = constPos(7, 7)  // h8
  private val blackQueenSideRook = constPos(0, 7)  // a8

  def update(rights: CastlingRights, boardBefore: Board, move: Move): CastlingRights =
    val moving   = boardBefore.pieceAt(move.from)
    val captured = boardBefore.pieceAt(move.to)

    val afterKingMove = moving match
      case Some(Piece(Color.White, PieceType.King)) if move.from == whiteKingStart =>
        rights.copy(whiteKingSide = false, whiteQueenSide = false)
      case Some(Piece(Color.Black, PieceType.King)) if move.from == blackKingStart =>
        rights.copy(blackKingSide = false, blackQueenSide = false)
      case _ => rights

    val afterRookMove = moving match
      case Some(Piece(Color.White, PieceType.Rook)) if move.from == whiteKingSideRook  =>
        afterKingMove.copy(whiteKingSide = false)
      case Some(Piece(Color.White, PieceType.Rook)) if move.from == whiteQueenSideRook =>
        afterKingMove.copy(whiteQueenSide = false)
      case Some(Piece(Color.Black, PieceType.Rook)) if move.from == blackKingSideRook  =>
        afterKingMove.copy(blackKingSide = false)
      case Some(Piece(Color.Black, PieceType.Rook)) if move.from == blackQueenSideRook =>
        afterKingMove.copy(blackQueenSide = false)
      case _ => afterKingMove

    captured match
      case Some(Piece(Color.White, PieceType.Rook)) if move.to == whiteKingSideRook  =>
        afterRookMove.copy(whiteKingSide = false)
      case Some(Piece(Color.White, PieceType.Rook)) if move.to == whiteQueenSideRook =>
        afterRookMove.copy(whiteQueenSide = false)
      case Some(Piece(Color.Black, PieceType.Rook)) if move.to == blackKingSideRook  =>
        afterRookMove.copy(blackKingSide = false)
      case Some(Piece(Color.Black, PieceType.Rook)) if move.to == blackQueenSideRook =>
        afterRookMove.copy(blackQueenSide = false)
      case _ => afterRookMove
