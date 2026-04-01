package chess.notation.fen

import chess.notation.api.{NotationFormat, NotationParser, ParsedNotation, ParseFailure}

/** Strict FEN parser that implements the shared [[NotationParser]] contract.
 *
 *  Parsing proceeds in four phases:
 *  1. Field splitting ([[FenTokenizer]])
 *  2. Per-field syntax/structural parsing ([[FenFieldParsers]])
 *  3. Assembly into the parser-local [[FenRecord]]
 *  4. Mapping to the thin shared [[ParsedNotation.ParsedFen]] result
 *
 *  On success the raw input string is preserved as-is in [[ParsedNotation.ParsedFen]].
 *  On any failure a structured [[ParseFailure]] is returned; input is never
 *  silently normalised.
 *
 *  Validation scope:
 *  - syntax and structural shape only (field count, rank count, legal symbols, etc.)
 *  - no semantic chess-legality checks (king placement, reachable positions, etc.)
 */
object FenParser extends NotationParser:

  val format: NotationFormat = NotationFormat.FEN

  /** Parse `input` and return `Right(ParsedFen(input))` on success. */
  def parse(input: String): Either[ParseFailure, ParsedNotation] =
    parseRecord(input).map(_ => ParsedNotation.ParsedFen(input))

  /** Parse `input` into a fully structured [[FenRecord]].
   *
   *  This is the parser-local result used by the FEN importer.
   *  It is not part of the shared notation API.
   *
   *  Returns the same failures as [[parse]]; the public [[parse]] method
   *  discards the [[FenRecord]] and returns the thin shared contract.
   */
  def parseRecord(input: String): Either[ParseFailure, FenRecord] =
    for
      tokens         <- FenTokenizer.tokenize(input)
      ranks          <- FenFieldParsers.parsePiecePlacement(tokens.piecePlacement)
      activeColor    <- FenFieldParsers.parseActiveColor(tokens.activeColor)
      castling       <- FenFieldParsers.parseCastling(tokens.castling)
      enPassant      <- FenFieldParsers.parseEnPassant(tokens.enPassant)
      halfmoveClock  <- FenFieldParsers.parseHalfmoveClock(tokens.halfmoveClock)
      fullmoveNumber <- FenFieldParsers.parseFullmoveNumber(tokens.fullmoveNumber)
    yield FenRecord(ranks, activeColor, castling, enPassant, halfmoveClock, fullmoveNumber)
