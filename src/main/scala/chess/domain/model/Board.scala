package chess.domain.model

/** An immutable chess board represented as a sparse map of positions to pieces.
 *
 *  Squares absent from the map are considered empty.
 */
final case class Board private (private val squares: Map[Position, Piece]):

  /** Return the piece at the given position, if any. */
  def pieceAt(pos: Position): Option[Piece] =
    squares.get(pos)

  /** Return a new Board with the piece placed at the given position,
   *  overwriting any existing piece there.
   */
  def place(pos: Position, piece: Piece): Board =
    Board(squares.updated(pos, piece))

  /** Return a new Board with the given position cleared. */
  def remove(pos: Position): Board =
    Board(squares.removed(pos))

  /** All pieces currently on the board as (position, piece) pairs. */
  def pieces: Seq[(Position, Piece)] = squares.toSeq

object Board:
  /** An empty board with no pieces. */
  val empty: Board = Board(Map.empty)

  /** Construct a Position from known-valid board constants.
   *  Throws AssertionError if coordinates are out of bounds — only call with
   *  hardcoded values that are guaranteed to be legal chess squares.
   */
  private[model] def constPos(file: Int, rank: Int): Position =
    Position.from(file, rank).getOrElse(throw AssertionError(s"Invalid board constant: file=$file rank=$rank"))

  /** The standard chess starting position with all 32 pieces. */
  val initial: Board =
    val backRank = List(
      PieceType.Rook, PieceType.Knight, PieceType.Bishop, PieceType.Queen,
      PieceType.King,  PieceType.Bishop, PieceType.Knight, PieceType.Rook
    )

    val whiteBack  = backRank.zipWithIndex.map { case (pt, f) => constPos(f, 0) -> Piece(Color.White, pt) }
    val blackBack  = backRank.zipWithIndex.map { case (pt, f) => constPos(f, 7) -> Piece(Color.Black, pt) }
    val whitePawns = (0 to 7).map(f => constPos(f, 1) -> Piece(Color.White, PieceType.Pawn))
    val blackPawns = (0 to 7).map(f => constPos(f, 6) -> Piece(Color.Black, PieceType.Pawn))

    Board((whiteBack ++ blackBack ++ whitePawns ++ blackPawns).toMap)
