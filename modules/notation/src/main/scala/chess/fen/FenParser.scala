package chess.notation.fen

import chess.notation.api.{FenData, NotationFormat, NotationParser, ParsedNotation, ParseFailure}

/** Strict FEN parser that implements the shared [[NotationParser]] contract.
 *
 *  Parsing proceeds in five phases:
 *  1. Field splitting ([[FenTokenizer]])
 *  2. Per-field syntax/structural parsing ([[FenFieldParsers]])
 *  3. Assembly into the parser-local [[FenRecord]]
 *  4. Conversion to the shared [[FenData]] model
 *  5. Wrapping in [[ParsedNotation.ParsedFen]]
 *
 *  On success the raw input string and the structured [[FenData]] are both
 *  preserved in [[ParsedNotation.ParsedFen]].  On any failure a structured
 *  [[ParseFailure]] is returned; input is never silently normalised.
 *
 *  Validation scope:
 *  - syntax and structural shape only (field count, rank count, legal symbols, etc.)
 *  - no semantic chess-legality checks (king placement, reachable positions, etc.)
 */
object FenParser extends NotationParser:

  val format: NotationFormat = NotationFormat.FEN

  /** Parse `input` and return `Right(ParsedFen(input, data))` on success. */
  def parse(input: String): Either[ParseFailure, ParsedNotation] =
    parseRecord(input).map(record => ParsedNotation.ParsedFen(input, toFenData(record)))

  /** Parse `input` into a fully structured [[FenRecord]].
   *
   *  This is a notation-module internal operation.  It is intentionally
   *  restricted to `package chess.notation.fen` and is NOT part of the shared
   *  `chess.notation.api` contract.
   *
   *  Returns the same failures as [[parse]]; the public [[parse]] method
   *  converts the [[FenRecord]] to [[FenData]] and wraps it in [[ParsedNotation.ParsedFen]].
   */
  private[fen] def parseRecord(input: String): Either[ParseFailure, FenRecord] =
    for
      tokens         <- FenTokenizer.tokenize(input)
      ranks          <- FenFieldParsers.parsePiecePlacement(tokens.piecePlacement)
      activeColor    <- FenFieldParsers.parseActiveColor(tokens.activeColor)
      castling       <- FenFieldParsers.parseCastling(tokens.castling)
      enPassant      <- FenFieldParsers.parseEnPassant(tokens.enPassant)
      halfmoveClock  <- FenFieldParsers.parseHalfmoveClock(tokens.halfmoveClock)
      fullmoveNumber <- FenFieldParsers.parseFullmoveNumber(tokens.fullmoveNumber)
    yield FenRecord(ranks, activeColor, castling, enPassant, halfmoveClock, fullmoveNumber)

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
