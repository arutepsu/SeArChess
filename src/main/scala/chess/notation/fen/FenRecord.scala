package chess.notation.fen

/** Parser-local color representation.
 *
 *  Corresponds to the FEN active-color tokens 'w' and 'b'.
 *  Not a domain type — the importer converts this to the domain Color.
 */
enum FenColor:
  case White, Black

/** Parser-local piece-type representation.
 *
 *  Corresponds to uppercase/lowercase FEN piece characters.
 *  Not a domain type — the importer converts this to the domain PieceType.
 */
enum FenPieceSymbol:
  case King, Queen, Rook, Bishop, Knight, Pawn

/** A single square as decoded from a FEN rank string. */
enum FenSquare:
  case Occupied(color: FenColor, symbol: FenPieceSymbol)
  case Empty

/** Castling availability decoded from the FEN castling field. */
final case class FenCastlingAvailability(
  whiteKingSide:  Boolean,
  whiteQueenSide: Boolean,
  blackKingSide:  Boolean,
  blackQueenSide: Boolean
)

object FenCastlingAvailability:
  /** Corresponds to the '-' castling token: no castling available. */
  val none: FenCastlingAvailability =
    FenCastlingAvailability(
      whiteKingSide  = false,
      whiteQueenSide = false,
      blackKingSide  = false,
      blackQueenSide = false
    )

/** En passant target decoded from the FEN en passant field. */
enum FenEnPassantTarget:
  /** Corresponds to the '-' en passant token: no en passant capture available. */
  case Absent

  /** A syntactically valid en passant square, 0-indexed.
   *
   *  @param file 0 = 'a' .. 7 = 'h'
   *  @param rank 0 = '1' .. 7 = '8'
   */
  case Square(file: Int, rank: Int)

/** Complete structured representation of a parsed FEN string.
 *
 *  This is a parser-local model internal to the `chess.notation.fen` package.
 *  It is NOT part of the shared notation API and must not be used as a contract.
 *
 *  `ranks` is ordered from FEN rank 8 (index 0) to FEN rank 1 (index 7),
 *  matching the left-to-right field order in the FEN string.  Any reordering
 *  needed by the domain board model is the importer's responsibility.
 */
private[fen] final case class FenRecord(
  ranks:          Vector[Vector[FenSquare]],
  activeColor:    FenColor,
  castling:       FenCastlingAvailability,
  enPassant:      FenEnPassantTarget,
  halfmoveClock:  Int,
  fullmoveNumber: Int
)
