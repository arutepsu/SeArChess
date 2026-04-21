package chess.notation.pgn

import chess.notation.api.ParseFailure
import scala.util.parsing.combinator.RegexParsers

/** Scala parser-combinator implementation of the PGN-core grammar.
  *
  * Scope:
  *   - tag pairs
  *   - mainline SAN tokens
  *   - result token
  *
  * Comments, NAGs, and variations are stripped first by [[PgnPreprocessor]]. SAN legality is
  * intentionally out of scope here.
  */
private[pgn] object PgnCombinatorGrammar extends RegexParsers, PgnGrammar:

  override val skipWhitespace: Boolean = true

  private val ResultTokens = Set("1-0", "0-1", "1/2-1/2", "*")

  override def parseRecord(input: String): Either[ParseFailure, PgnRecord] =
    if input.isBlank then Left(ParseFailure.UnexpectedEndOfInput("PGN input is empty"))
    else
      val cleaned = PgnPreprocessor.stripAnnotations(input)
      parseAll(document, cleaned) match
        case Success(record, _) =>
          Right(record)
        case NoSuccess(msg, next) =>
          val pos = next.pos
          Left(
            ParseFailure.SyntaxError(
              message = s"[line ${pos.line}, column ${pos.column}] failed parsing PGN: $msg",
              line = Some(pos.line),
              column = Some(pos.column)
            )
          )

  // ── Top-level document ────────────────────────────────────────────────────

  private def document: Parser[PgnRecord] =
    headers ~ moveSection ^^ { case hdrs ~ (moves, result) =>
      PgnRecord(
        headers = hdrs.toMap,
        moveTokens = moves,
        result = result
      )
    }

  // ── Headers ───────────────────────────────────────────────────────────────

  private def headers: Parser[Vector[(String, String)]] =
    rep(tagPair) ^^ (_.toVector)

  private def tagPair: Parser[(String, String)] =
    "[" ~> tagKey ~ quotedValue <~ "]" ^^ { case k ~ v =>
      (k, v)
    }

  private def tagKey: Parser[String] =
    """[A-Za-z][A-Za-z0-9_]*""".r

  private def quotedValue: Parser[String] =
    "\"" ~> """[^"]*""".r <~ "\""

  // ── Move section ──────────────────────────────────────────────────────────

  private def moveSection: Parser[(Vector[String], Option[String])] =
    rep(moveItem) ^^ { items =>
      val result = items.collectFirst { case MoveItem.Result(r) => r }
      val moves = items.collect { case MoveItem.San(s) => s }.toVector
      (moves, result)
    }

  private def moveItem: Parser[MoveItem] =
    resultToken ^^ MoveItem.Result.apply |
      moveNumber ^^^ MoveItem.Ignore |
      sanToken ^^ MoveItem.San.apply

  private def moveNumber: Parser[String] =
    """\d+\.+""".r

  private def resultToken: Parser[String] =
    "1/2-1/2" | "1-0" | "0-1" | "*"

  /** SAN token kept intentionally permissive.
    *
    * Legality and deeper SAN interpretation happen later in [[SanResolver]].
    */
  private def sanToken: Parser[String] =
    """[^\s\[\]]+""".r ^? (
      { case token if !ResultTokens.contains(token) => token },
      token => s"unexpected result token in SAN position: '$token'"
    )

  // ── Internal move-section model ───────────────────────────────────────────

  private enum MoveItem:
    case San(value: String)
    case Result(value: String)
    case Ignore
