package chess.notation.fen

import chess.notation.api.{FenData, NotationFormat, NotationParser, ParsedNotation, ParseFailure}

/** Strict FEN parser that implements the shared [[NotationParser]] contract.
 *
 *  Parsing flow:
 *  1. Full grammar parse via [[FenCombinatorGrammar]]
 *  2. Assembly into parser-local [[FenRecord]]
 *  3. Conversion to shared [[FenData]]
 *  4. Wrapping in [[ParsedNotation.ParsedFen]]
 *
 *  Validation scope:
 *  - syntax and structural shape only
 *  - no semantic chess-legality checks
 */
object FenParser extends NotationParser:

  val format: NotationFormat = NotationFormat.FEN

  val default: FenGrammar = FenCombinatorGrammar // selector for easy switching between combinator and FastParse implementations

  def parse(input: String): Either[ParseFailure, ParsedNotation] =
    parseRecord(input).map(record => ParsedNotation.ParsedFen(input, toFenData(record)))

  private[fen] def parseRecord(input: String): Either[ParseFailure, FenRecord] =
    FenGrammarSelector.default.parseRecord(input)

  // ── FenRecord → FenData conversion ──────────────────────────────────────────

  private def toFenData(record: FenRecord): FenData =
    FenData(
      ranks          = record.ranks.map(_.map(toFenDataSquare)),
      activeColor    = toFenDataColor(record.activeColor),
      castling       = toFenDataCastling(record.castling),
      enPassant      = toFenDataEnPassant(record.enPassant),
      halfmoveClock  = record.halfmoveClock,
      fullmoveNumber = record.fullmoveNumber
    )

  private def toFenDataColor(c: FenColor): FenData.ActiveColor = c match
    case FenColor.White => FenData.ActiveColor.White
    case FenColor.Black => FenData.ActiveColor.Black

  private def toFenDataSymbol(s: FenPieceSymbol): FenData.PieceSymbol = s match
    case FenPieceSymbol.King   => FenData.PieceSymbol.King
    case FenPieceSymbol.Queen  => FenData.PieceSymbol.Queen
    case FenPieceSymbol.Rook   => FenData.PieceSymbol.Rook
    case FenPieceSymbol.Bishop => FenData.PieceSymbol.Bishop
    case FenPieceSymbol.Knight => FenData.PieceSymbol.Knight
    case FenPieceSymbol.Pawn   => FenData.PieceSymbol.Pawn

  private def toFenDataSquare(sq: FenSquare): FenData.Square = sq match
    case FenSquare.Occupied(c, s) => FenData.Square.Occupied(toFenDataColor(c), toFenDataSymbol(s))
    case FenSquare.Empty          => FenData.Square.Empty

  private def toFenDataCastling(c: FenCastlingAvailability): FenData.CastlingAvailability =
    FenData.CastlingAvailability(c.whiteKingSide, c.whiteQueenSide, c.blackKingSide, c.blackQueenSide)

  private def toFenDataEnPassant(ep: FenEnPassantTarget): FenData.EnPassantTarget = ep match
    case FenEnPassantTarget.Absent             => FenData.EnPassantTarget.Absent
    case FenEnPassantTarget.Square(file, rank) => FenData.EnPassantTarget.Square(file, rank)