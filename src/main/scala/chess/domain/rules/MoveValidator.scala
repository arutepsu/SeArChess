package chess.domain.rules

import chess.domain.error.DomainError
import chess.domain.model.*

/** Validates that a move is legal for the piece at the source square.
 *
 *  Precondition: the caller has already confirmed a piece exists at move.from.
 *  Checks:
 *    1. same-square rejection
 *    2. own-piece target rejection
 *    3. piece-type movement pattern
 *    4. path clearance for sliding pieces (rook, bishop, queen)
 */
object MoveValidator:

  def validate(board: Board, piece: Piece, move: Move): Either[DomainError, Unit] =
    if move.from == move.to then
      Left(DomainError.SameSquare)
    else
      board.pieceAt(move.to) match
        case Some(target) if target.color == piece.color =>
          Left(DomainError.OccupiedByOwnPiece(move.to))
        case _ =>
          validatePattern(board, piece, move)

  // ── piece-type dispatch ────────────────────────────────────────────────────

  private def validatePattern(board: Board, piece: Piece, move: Move): Either[DomainError, Unit] =
    piece.pieceType match
      case PieceType.Rook   => validateRook(board, move)
      case PieceType.Bishop => validateBishop(board, move)
      case PieceType.Queen  => validateQueen(board, move)
      case PieceType.Knight => validateKnight(move)
      case PieceType.King   => validateKing(move)
      case PieceType.Pawn   => validatePawn(board, piece.color, move)

  // ── sliding pieces ─────────────────────────────────────────────────────────

  private def validateRook(board: Board, move: Move): Either[DomainError, Unit] =
    val straight = move.to.file == move.from.file || move.to.rank == move.from.rank
    if !straight then illegal(move)
    else if isPathClear(board, move) then ok
    else blocked(move)

  private def validateBishop(board: Board, move: Move): Either[DomainError, Unit] =
    val df = math.abs(move.to.file - move.from.file)
    val dr = math.abs(move.to.rank - move.from.rank)
    if df != dr then illegal(move)
    else if isPathClear(board, move) then ok
    else blocked(move)

  private def validateQueen(board: Board, move: Move): Either[DomainError, Unit] =
    val df       = move.to.file - move.from.file
    val dr       = move.to.rank - move.from.rank
    val straight = df == 0 || dr == 0
    val diagonal = math.abs(df) == math.abs(dr)
    if !straight && !diagonal then illegal(move)
    else if isPathClear(board, move) then ok
    else blocked(move)

  // ── non-sliding pieces ─────────────────────────────────────────────────────

  private def validateKnight(move: Move): Either[DomainError, Unit] =
    val df = math.abs(move.to.file - move.from.file)
    val dr = math.abs(move.to.rank - move.from.rank)
    if (df == 1 && dr == 2) || (df == 2 && dr == 1) then ok else illegal(move)

  private def validateKing(move: Move): Either[DomainError, Unit] =
    val df = math.abs(move.to.file - move.from.file)
    val dr = math.abs(move.to.rank - move.from.rank)
    if df <= 1 && dr <= 1 then ok else illegal(move)

  // ── pawn ───────────────────────────────────────────────────────────────────

  private def validatePawn(board: Board, color: Color, move: Move): Either[DomainError, Unit] =
    val direction = if color == Color.White then 1 else -1
    val startRank = if color == Color.White then 1 else 6
    val df        = move.to.file - move.from.file
    val dr        = move.to.rank - move.from.rank

    if dr == direction && df == 0 then
      // single-square forward: target must be empty
      if board.pieceAt(move.to).isDefined then illegal(move) else ok

    else if dr == 2 * direction && df == 0 && move.from.rank == startRank then
      // double-square forward from starting rank: both squares must be empty
      val mid = Position.from(move.from.file, move.from.rank + direction).toOption.get
      if board.pieceAt(mid).isDefined || board.pieceAt(move.to).isDefined then blocked(move) else ok

    else if dr == direction && math.abs(df) == 1 then
      // diagonal capture: target must hold an enemy piece
      board.pieceAt(move.to) match
        case Some(target) if target.color != color => ok
        case _                                     => illegal(move)

    else
      illegal(move)

  // ── helpers ────────────────────────────────────────────────────────────────

  /** True if every square strictly between from and to is empty. */
  private def isPathClear(board: Board, move: Move): Boolean =
    val stepFile = Integer.signum(move.to.file - move.from.file)
    val stepRank = Integer.signum(move.to.rank - move.from.rank)
    LazyList
      .iterate((move.from.file + stepFile, move.from.rank + stepRank)) {
        case (f, r) => (f + stepFile, r + stepRank)
      }
      .takeWhile { case (f, r) => f != move.to.file || r != move.to.rank }
      .forall    { case (f, r) => Position.from(f, r).toOption.flatMap(board.pieceAt).isEmpty }

  private val ok: Either[DomainError, Unit]                        = Right(())
  private def illegal(m: Move): Left[DomainError.IllegalMove, Nothing]  = Left(DomainError.IllegalMove(m.from, m.to))
  private def blocked(m: Move): Left[DomainError.BlockedPath, Nothing]  = Left(DomainError.BlockedPath(m.from, m.to))
