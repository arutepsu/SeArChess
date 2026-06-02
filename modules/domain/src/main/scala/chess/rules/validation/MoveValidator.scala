package chess.domain.rules.validation

import chess.domain.error.DomainError
import chess.domain.model.*

/** Validates that a move is legal for the piece at the source square.
  *
  * Precondition: the caller has already confirmed a piece exists at move.from. Checks:
  *   1. same-square rejection 2. own-piece target rejection 3. piece-type movement pattern 4. path
  *      clearance for sliding pieces (rook, bishop, queen)
  */
object MoveValidator:

  def validate(board: Board, piece: Piece, move: Move): Either[DomainError, Unit] =
    if move.from == move.to then Left(DomainError.SameSquare)
    else
      board.pieceAt(move.to) match
        case Some(target) if target.color == piece.color =>
          Left(DomainError.OccupiedByOwnPiece(move.to))
        case _ =>
          validatePattern(board, piece, move)

  /** Return true if `piece` sitting at `from` attacks `to` on this board.
    *
    * Unlike validate(), this method:
    *   - ignores the color of any piece at `to` (needed for king-safety checks)
    *   - for pawns, considers only diagonal attacks, not forward moves
    *   - never recurses into check detection
    */
  def canAttack(board: Board, piece: Piece, from: Position, to: Position): Boolean =
    if from == to then false
    else
      piece.pieceType match
        case PieceType.Rook   => rookCanMove(board, from, to)
        case PieceType.Bishop => bishopCanMove(board, from, to)
        case PieceType.Queen  => queenCanMove(board, from, to)
        case PieceType.Knight => knightCanMove(from, to)
        case PieceType.King   => kingCanMove(from, to)
        case PieceType.Pawn   => pawnAttacks(piece.color, from, to)

  private def pawnAttacks(color: Color, from: Position, to: Position): Boolean =
    val direction = if color == Color.White then 1 else -1
    to.rank - from.rank == direction && math.abs(to.file - from.file) == 1

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
    val straight = isStraight(move.from, move.to)
    if !straight then illegal(move)
    else if isPathClear(board, move.from, move.to) then ok
    else blocked(move)

  private def validateBishop(board: Board, move: Move): Either[DomainError, Unit] =
    if !isDiagonal(move.from, move.to) then illegal(move)
    else if isPathClear(board, move.from, move.to) then ok
    else blocked(move)

  private def validateQueen(board: Board, move: Move): Either[DomainError, Unit] =
    if !queenPattern(move.from, move.to) then illegal(move)
    else if isPathClear(board, move.from, move.to) then ok
    else blocked(move)

  // ── non-sliding pieces ─────────────────────────────────────────────────────

  private def validateKnight(move: Move): Either[DomainError, Unit] =
    if knightCanMove(move.from, move.to) then ok else illegal(move)

  private def validateKing(move: Move): Either[DomainError, Unit] =
    if kingCanMove(move.from, move.to) then ok else illegal(move)

  // ── pawn ───────────────────────────────────────────────────────────────────

  private def validatePawn(board: Board, color: Color, move: Move): Either[DomainError, Unit] =
    val direction = if color == Color.White then 1 else -1
    val startRank = if color == Color.White then 1 else 6
    val df = move.to.file - move.from.file
    val dr = move.to.rank - move.from.rank

    if dr == direction && df == 0 then
      // single-square forward: target must be empty
      if board.pieceAt(move.to).isDefined then illegal(move) else ok
    else if dr == 2 * direction && df == 0 && move.from.rank == startRank then
      // double-square forward from starting rank: both squares must be empty
      val mid = pawnMidSquare(move.from.file, move.from.rank + direction)
      if board.pieceAt(mid).isDefined || board.pieceAt(move.to).isDefined then blocked(move) else ok
    else if dr == direction && math.abs(df) == 1 then
      // diagonal capture: target must hold an enemy piece
      board.pieceAt(move.to) match
        case Some(target) if target.color != color => ok
        case _                                     => illegal(move)
    else illegal(move)

  // ── helpers ────────────────────────────────────────────────────────────────

  /** Compute the intermediate square for a pawn double-advance from known-valid coordinates. Throws
    * AssertionError if coordinates are out of bounds — unreachable under normal pawn rules.
    */
  private[validation] def pawnMidSquare(file: Int, rank: Int): Position =
    Position
      .from(file, rank)
      .getOrElse(
        throw AssertionError(
          "Invalid pawn mid-square — unreachable for a pawn on its starting rank"
        )
      )

  /** True if every square strictly between from and to is empty.
    *
    * Uses a while loop instead of a LazyList pipeline to avoid allocating Cons nodes and
    * (Int, Int) tuples on every call — this method is on the hot path of legal-move generation
    * and check detection.
    */
  private def rookCanMove(board: Board, from: Position, to: Position): Boolean =
    isStraight(from, to) && isPathClear(board, from, to)

  private def bishopCanMove(board: Board, from: Position, to: Position): Boolean =
    isDiagonal(from, to) && isPathClear(board, from, to)

  private def queenCanMove(board: Board, from: Position, to: Position): Boolean =
    queenPattern(from, to) && isPathClear(board, from, to)

  private def knightCanMove(from: Position, to: Position): Boolean =
    val df = math.abs(to.file - from.file)
    val dr = math.abs(to.rank - from.rank)
    (df == 1 && dr == 2) || (df == 2 && dr == 1)

  private def kingCanMove(from: Position, to: Position): Boolean =
    val df = math.abs(to.file - from.file)
    val dr = math.abs(to.rank - from.rank)
    df <= 1 && dr <= 1

  private def isStraight(from: Position, to: Position): Boolean =
    to.file == from.file || to.rank == from.rank

  private def isDiagonal(from: Position, to: Position): Boolean =
    math.abs(to.file - from.file) == math.abs(to.rank - from.rank)

  private def queenPattern(from: Position, to: Position): Boolean =
    isStraight(from, to) || isDiagonal(from, to)

  private def isPathClear(board: Board, from: Position, to: Position): Boolean =
    val stepFile = Integer.signum(to.file - from.file)
    val stepRank = Integer.signum(to.rank - from.rank)
    var f = from.file + stepFile
    var r = from.rank + stepRank
    var clear = true
    while clear && (f != to.file || r != to.rank) do
      clear = Position.from(f, r).toOption.flatMap(board.pieceAt).isEmpty
      f += stepFile
      r += stepRank
    clear

  private val ok: Either[DomainError, Unit] = Right(())
  private def illegal(m: Move): Left[DomainError.IllegalMove, Nothing] = Left(
    DomainError.IllegalMove(m.from, m.to)
  )
  private def blocked(m: Move): Left[DomainError.BlockedPath, Nothing] = Left(
    DomainError.BlockedPath(m.from, m.to)
  )
