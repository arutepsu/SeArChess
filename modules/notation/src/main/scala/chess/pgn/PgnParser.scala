package chess.notation.pgn

import chess.notation.api.{ParseFailure, ParsedNotation, PgnData}

/** Thin PGN parser adapter.
  *
  * Delegates raw parsing to the selected [[PgnGrammar]], then converts the parser-local
  * [[PgnRecord]] into shared [[PgnData]] and wraps it as [[ParsedNotation.ParsedPgn]].
  */
private[pgn] object PgnParser:

  private val grammar: PgnGrammar = PgnGrammarSelector.default

  val default: PgnGrammar = PgnFastParseGrammar

  def parse(input: String): Either[ParseFailure, PgnData] =
    grammar.parseRecord(input).map(toPgnData)

  def parseParsedNotation(input: String): Either[ParseFailure, ParsedNotation.ParsedPgn] =
    parse(input).map(data => ParsedNotation.ParsedPgn(raw = input, data = data))

  private def toPgnData(record: PgnRecord): PgnData =
    PgnData(
      headers = record.headers,
      moveTokens = record.moveTokens,
      result = record.result
    )
