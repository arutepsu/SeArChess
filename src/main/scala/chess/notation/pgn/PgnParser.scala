package chess.notation.pgn

import chess.notation.api.{ParseFailure, PgnData}

/** PGN document parser.
 *
 *  Produces a [[PgnData]] from a raw PGN string.  This is a Stage-2
 *  implementation: it extracts headers, move tokens, and the result token.
 *
 *  Parsing scope:
 *  - header tag pairs (e.g. `[White "Alice"]`)
 *  - mainline SAN tokens (e.g. `e4`, `Nf3`, `O-O`)
 *  - game result token (`1-0`, `0-1`, `1/2-1/2`, `*`)
 *
 *  Deliberately not handled in this stage:
 *  - SAN legality or board-state validation
 *  - move number continuity or completeness checks
 *  - nested variations — innermost parenthesized groups are stripped
 *    iteratively, so deeply nested ones are reduced correctly
 *  - NAGs (`$N`) — stripped silently
 *  - clock annotations embedded in comments (`{[%clk ...]}`) — stripped
 *    with the enclosing comment
 *
 *  Visible only within the `chess.notation.pgn` package.
 */
private[pgn] object PgnParser:

  private val TagPairRe    = """\[([A-Za-z]\w*)\s+"([^"]*)"\]""".r
  private val ResultTokens = Set("1-0", "0-1", "1/2-1/2", "*")

  /** Parse `input` into a [[PgnData]], or return a [[ParseFailure]].
   *
   *  An empty or blank input is always rejected with
   *  [[ParseFailure.UnexpectedEndOfInput]].  Any non-empty input is parsed
   *  best-effort: an all-header PGN with no moves is valid and produces an
   *  empty `moveTokens` vector; a bare move list with no headers is valid and
   *  produces an empty `headers` map.
   */
  def parse(input: String): Either[ParseFailure, PgnData] =
    if input.isBlank then
      Left(ParseFailure.UnexpectedEndOfInput("PGN input is empty"))
    else
      val headers          = extractHeaders(input)
      val cleanText        = stripAnnotations(input)
      val (tokens, result) = tokenize(cleanText)
      Right(PgnData(headers, tokens, result))

  // ── Header extraction ─────────────────────────────────────────────────────

  private def extractHeaders(input: String): Map[String, String] =
    TagPairRe.findAllMatchIn(input).map(m => m.group(1) -> m.group(2)).toMap

  // ── Move-text cleaning ────────────────────────────────────────────────────

  /** Remove everything that is not a move token or result from `input`.
   *
   *  Strips in order:
   *  1. header tag pairs  `[Key "value"]`
   *  2. comments          `{...}`
   *  3. parenthesized variations  `(...)` — iteratively to handle nesting
   *  4. NAGs              `$N`
   */
  private def stripAnnotations(input: String): String =
    val noTags       = TagPairRe.replaceAllIn(input, " ")
    val noComments   = noTags.replaceAll("""\{[^}]*\}""", " ")
    val noVariations = stripNestedVariations(noComments)
    noVariations.replaceAll("""\$\d+""", " ")

  /** Repeatedly strip innermost `(...)` groups until none remain. */
  private def stripNestedVariations(text: String): String =
    var current  = text
    var previous = ""
    while current != previous do
      previous = current
      current  = current.replaceAll("""\([^()]*\)""", " ")
    current

  // ── Tokenization ──────────────────────────────────────────────────────────

  private def tokenize(cleanText: String): (Vector[String], Option[String]) =
    val allTokens = cleanText.split("\\s+").filter(_.nonEmpty).toVector
    val result    = allTokens.find(ResultTokens.contains)
    val moves     = allTokens.filterNot(t =>
      ResultTokens.contains(t) || isMoveNumber(t) || isNag(t)
    )
    (moves, result)

  /** Move number tokens: `1.`, `1...`, `42.` etc. */
  private def isMoveNumber(token: String): Boolean =
    token.matches("""^\d+\.+$""")

  /** Numeric annotation glyph: `$N`. */
  private def isNag(token: String): Boolean =
    token.matches("""^\$\d+$""")
