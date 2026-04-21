package chess.domain.rules.validation

import chess.domain.error.DomainError
import chess.domain.model.*
import chess.domain.state.CastlingRights

/** Validates whether a king move is a legal castling move.
  *
  * Responsibilities:
  *   - identify the four castling king moves (e1→g1, e1→c1, e8→g8, e8→c8)
  *   - check castling right availability
  *   - verify the rook is on its original square
  *   - verify the path between king and rook is empty
  *   - verify the king is not currently in check
  *   - verify the king's traversal squares are not attacked
  *
  * Returns Right(true) for king-side, Right(false) for queen-side on success.
  */
object CastlingValidator:

  private[validation] def constPos(file: Int, rank: Int): Position =
    Position
      .from(file, rank)
      .getOrElse(throw AssertionError(s"Invalid castling constant: file=$file rank=$rank"))

  private val whiteKingFrom = constPos(4, 0) // e1
  private val blackKingFrom = constPos(4, 7) // e8
  private val whiteKingSideTo = constPos(6, 0) // g1
  private val whiteQueenSideTo = constPos(2, 0) // c1
  private val blackKingSideTo = constPos(6, 7) // g8
  private val blackQueenSideTo = constPos(2, 7) // c8

  /** True if the move matches one of the four castling king moves. */
  def isCastlingMove(move: Move): Boolean =
    (move.from == whiteKingFrom && (move.to == whiteKingSideTo || move.to == whiteQueenSideTo)) ||
      (move.from == blackKingFrom && (move.to == blackKingSideTo || move.to == blackQueenSideTo))

  /** Validate a castling attempt.  Returns Right(kingSide) on success. */
  def validate(
      board: Board,
      color: Color,
      move: Move,
      rights: CastlingRights
  ): Either[DomainError, Boolean] =
    val kingSide = move.to.file == 6 // g-file → king-side, c-file → queen-side
    val opp = color.opposite
    for
      _ <- checkRight(color, kingSide, rights)
      _ <- checkRook(board, color, kingSide)
      _ <- checkPathEmpty(board, color, kingSide)
      _ <- checkKingNotInCheck(board, move.from, opp)
      _ <- checkKingPathSafe(board, color, kingSide, opp)
    yield kingSide

  // ── private helpers ────────────────────────────────────────────────────────

  private def checkRight(
      color: Color,
      kingSide: Boolean,
      rights: CastlingRights
  ): Either[DomainError, Unit] =
    val ok = (color, kingSide) match
      case (Color.White, true)  => rights.whiteKingSide
      case (Color.White, false) => rights.whiteQueenSide
      case (Color.Black, true)  => rights.blackKingSide
      case (Color.Black, false) => rights.blackQueenSide
    Either.cond(ok, (), DomainError.CastleNotAllowed)

  private def checkRook(board: Board, color: Color, kingSide: Boolean): Either[DomainError, Unit] =
    val sq = rookOrigin(color, kingSide)
    board.pieceAt(sq) match
      case Some(Piece(c, PieceType.Rook)) if c == color => Right(())
      case _                                            => Left(DomainError.MissingCastlingRook)

  private def checkPathEmpty(
      board: Board,
      color: Color,
      kingSide: Boolean
  ): Either[DomainError, Unit] =
    Either.cond(
      squaresBetween(color, kingSide).forall(board.pieceAt(_).isEmpty),
      (),
      DomainError.CastlePathBlocked
    )

  private def checkKingNotInCheck(
      board: Board,
      kingPos: Position,
      opp: Color
  ): Either[DomainError, Unit] =
    Either.cond(
      !CheckValidator.isSquareAttacked(board, kingPos, opp),
      (),
      DomainError.CastleThroughCheck
    )

  private def checkKingPathSafe(
      board: Board,
      color: Color,
      kingSide: Boolean,
      opp: Color
  ): Either[DomainError, Unit] =
    Either.cond(
      kingTravelSquares(color, kingSide).forall(!CheckValidator.isSquareAttacked(board, _, opp)),
      (),
      DomainError.CastleThroughCheck
    )

  // ── square geometry ────────────────────────────────────────────────────────

  private def rank(color: Color): Int = if color == Color.White then 0 else 7

  /** The rook's original square. */
  def rookOrigin(color: Color, kingSide: Boolean): Position =
    val r = rank(color)
    if kingSide then constPos(7, r) else constPos(0, r) // h or a file

  /** The rook's destination square after castling. */
  def rookDestination(color: Color, kingSide: Boolean): Position =
    val r = rank(color)
    if kingSide then constPos(5, r) else constPos(3, r) // f or d file

  /** Squares that must be empty between king and rook (inclusive of all intermediate files). */
  private def squaresBetween(color: Color, kingSide: Boolean): List[Position] =
    val r = rank(color)
    if kingSide then List(constPos(5, r), constPos(6, r)) // f, g
    else List(constPos(1, r), constPos(2, r), constPos(3, r)) // b, c, d

  /** Squares the king passes through and lands on — must not be attacked. */
  private def kingTravelSquares(color: Color, kingSide: Boolean): List[Position] =
    val r = rank(color)
    if kingSide then List(constPos(5, r), constPos(6, r)) // f, g
    else
      List(constPos(3, r), constPos(2, r)) // d, c  (b is empty-required but king doesn't cross it)
