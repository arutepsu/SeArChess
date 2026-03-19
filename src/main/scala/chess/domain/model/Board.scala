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

object Board:
  /** An empty board with no pieces. */
  val empty: Board = Board(Map.empty)
