package chess.notation.api

/** Structured representation of a parsed FEN string.
  *
  * Produced by the FEN parser and embedded in [[ParsedNotation.ParsedFen]]. Carries the six FEN
  * fields in a typed form so that the importer never needs to re-parse the raw string.
  *
  * The nested types mirror the six FEN fields without coupling the shared notation contract to
  * implementation-specific parser types in `chess.notation.fen`.
  *
  * `ranks` is ordered from FEN rank 8 (index 0) to FEN rank 1 (index 7), matching the left-to-right
  * field order in the FEN string.
  */
final case class FenData(
    ranks: Vector[Vector[FenData.Square]],
    activeColor: FenData.ActiveColor,
    castling: FenData.CastlingAvailability,
    enPassant: FenData.EnPassantTarget,
    halfmoveClock: Int,
    fullmoveNumber: Int
)

object FenData:

  enum ActiveColor:
    case White, Black

  enum PieceSymbol:
    case King, Queen, Rook, Bishop, Knight, Pawn

  enum Square:
    case Occupied(color: ActiveColor, symbol: PieceSymbol)
    case Empty

  final case class CastlingAvailability(
      whiteKingSide: Boolean,
      whiteQueenSide: Boolean,
      blackKingSide: Boolean,
      blackQueenSide: Boolean
  )

  enum EnPassantTarget:
    case Absent

    /** A syntactically valid en passant square, 0-indexed.
      *
      * @param file
      *   0 = 'a' .. 7 = 'h'
      * @param rank
      *   0 = '1' .. 7 = '8'
      */
    case Square(file: Int, rank: Int)
