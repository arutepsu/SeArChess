package chess.domain.state

/** Tracks which castling moves are still legally available.
 *
 *  A right becomes permanently false once:
 *  - the corresponding king moves, OR
 *  - the corresponding rook moves from its original square, OR
 *  - a rook is captured on its original square.
 */
final case class CastlingRights(
  whiteKingSide:  Boolean,
  whiteQueenSide: Boolean,
  blackKingSide:  Boolean,
  blackQueenSide: Boolean
)

object CastlingRights:
  /** All four rights available — the starting state of every new game. */
  val full: CastlingRights = CastlingRights(true, true, true, true)

  /** No castling rights — useful for tests that do not involve castling. */
  val none: CastlingRights = CastlingRights(false, false, false, false)
