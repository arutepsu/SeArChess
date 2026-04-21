package chess.notation.pgn

import chess.notation.api.ParseFailure

/** Regex/manual PGN-core parser.
  *
  * Produces a parser-local [[PgnRecord]] from raw PGN input.
  *
  * Parsing scope:
  *   - header tag pairs
  *   - mainline SAN tokens
  *   - result token
  *
  * Deliberately not handled here:
  *   - SAN legality or board-state validation
  *   - move number continuity or completeness checks
  *   - preserving comments / variations / NAGs as syntax nodes
  */
private[pgn] object PgnRegexGrammar extends PgnGrammar:

  private val TagPairRe = """\[([A-Za-z]\w*)\s+"([^"]*)"\]""".r
  private val ResultTokens = Set("1-0", "0-1", "1/2-1/2", "*")

  override def parseRecord(input: String): Either[ParseFailure, PgnRecord] =
    if input.isBlank then Left(ParseFailure.UnexpectedEndOfInput("PGN input is empty"))
    else
      val headers = extractHeaders(input)
      val cleanText = PgnPreprocessor.stripAnnotations(input)
      val moveTextOnly = TagPairRe.replaceAllIn(cleanText, " ")
      val (tokens, result) = tokenize(moveTextOnly)
      Right(PgnRecord(headers, tokens, result))

  // ── Header extraction ─────────────────────────────────────────────────────

  private def extractHeaders(input: String): Map[String, String] =
    TagPairRe.findAllMatchIn(input).map(m => m.group(1) -> m.group(2)).toMap

  // ── Tokenization ──────────────────────────────────────────────────────────

  private def tokenize(cleanText: String): (Vector[String], Option[String]) =
    val allTokens = cleanText.split("\\s+").filter(_.nonEmpty).toVector
    val result = allTokens.find(ResultTokens.contains)
    val moves = allTokens.filterNot(t => ResultTokens.contains(t) || isMoveNumber(t) || isNag(t))
    (moves, result)

  private def isMoveNumber(token: String): Boolean =
    token.matches("""^\d+\.+$""")

  private def isNag(token: String): Boolean =
    token.matches("""^\$\d+$""")
