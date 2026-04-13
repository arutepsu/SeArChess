package chess.notation.pgn

import chess.notation.api.ParseFailure
import fastparse.*
import NoWhitespace.*

/** FastParse implementation of the PGN-core grammar.
 *
 *  Scope:
 *  - tag pairs
 *  - mainline SAN tokens
 *  - result token
 *
 *  Comments, NAGs, and variations are stripped first by [[PgnPreprocessor]].
 *  SAN legality is intentionally out of scope here.
 */
private[pgn] object PgnFastParseGrammar extends PgnGrammar:

  private val ResultTokens = Set("1-0", "0-1", "1/2-1/2", "*")

  override def parseRecord(input: String): Either[ParseFailure, PgnRecord] =
    if input.isBlank then
      Left(ParseFailure.UnexpectedEndOfInput("PGN input is empty"))
    else
      val cleaned = PgnPreprocessor.stripAnnotations(input)
      fastparse.parse(cleaned, p => document(using p)) match
        case Parsed.Success(record, _) =>
          Right(record)
        case f: Parsed.Failure =>
          Left(ParseFailure.SyntaxError(
            message = s"[line 1, column ${f.index + 1}] failed parsing PGN: ${f.trace().longMsg}",
            line = Some(1),
            column = Some(f.index + 1)
          ))

  // ── Top-level document ────────────────────────────────────────────────────

  private def document[$: P]: P[PgnRecord] =
    P(ws ~ headers ~ ws ~ moveSection ~ ws ~ End).map { case (hdrs, movesAndResult) =>
      val (moves, result) = movesAndResult
      PgnRecord(
        headers = hdrs.toMap,
        moveTokens = moves,
        result = result
      )
    }

  // ── Headers ───────────────────────────────────────────────────────────────

  private def headers[$: P]: P[Vector[(String, String)]] =
    P(tagPair.rep.map(_.toVector))

  private def tagPair[$: P]: P[(String, String)] =
    P("[" ~/ ws ~ tagKey ~ ws ~ quotedValue ~ ws ~ "]" ~ ws).map { case (k, v) =>
      (k, v)
    }

  private def tagKey[$: P]: P[String] =
        P((CharIn("A-Z", "a-z") ~ CharsWhileIn("A-Za-z0-9_", min = 0)).!)
            .opaque("tag key")

  private def quotedValue[$: P]: P[String] =
    P("\"" ~/ CharsWhile(_ != '"').! ~ "\"").opaque("quoted header value")

  // ── Move section ──────────────────────────────────────────────────────────

  private def moveSection[$: P]: P[(Vector[String], Option[String])] =
    P(moveItem.rep.map(_.toVector)).map { items =>
      val result = items.collectFirst { case MoveItem.Result(r) => r }
      val moves  = items.collect { case MoveItem.San(s) => s }
      (moves, result)
    }

  private def moveItem[$: P]: P[MoveItem] =
    P(
      ws ~
      (
        resultToken.map(MoveItem.Result.apply) |
        moveNumber.map(_ => MoveItem.Ignore)   |
        sanToken.map(MoveItem.San.apply)
      ) ~
      ws
    )

  private def moveNumber[$: P]: P[String] =
    P((CharsWhileIn("0-9", min = 1) ~ ".".rep(1)).!).opaque("move number")

  private def resultToken[$: P]: P[String] =
    P(StringIn("1/2-1/2", "1-0", "0-1", "*").!).opaque("result")

  /** SAN token kept intentionally permissive.
   *
   *  Legality and deeper SAN interpretation happen later in [[SanResolver]].
   *  This parser just extracts token boundaries for the PGN core.
   */
  private def sanToken[$: P]: P[String] =
    P(CharsWhile(ch => !ch.isWhitespace && ch != '[' && ch != ']', min = 1).!).filter(!ResultTokens.contains(_)).opaque("SAN token")

  // ── Whitespace ────────────────────────────────────────────────────────────

  private def ws[$: P]: P[Unit] =
    P(CharsWhileIn(" \r\n\t", min = 0))
  
  // ── Internal move-section model ───────────────────────────────────────────

  private enum MoveItem:
    case San(value: String)
    case Result(value: String)
    case Ignore