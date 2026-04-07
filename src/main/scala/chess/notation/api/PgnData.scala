package chess.notation.api

/** Structured representation of a parsed PGN document.
 *
 *  Mirrors the role of [[FenData]] for FEN: produced by the PGN parser and
 *  carried inside [[ParsedNotation.ParsedPgn]] so importers and exporters
 *  never need to re-parse the raw text.
 *
 *  Scope for Stage 2 of the PGN pipeline:
 *  - headers are extracted and keyed by tag name
 *  - move tokens are the raw SAN strings from the mainline, with move numbers,
 *    NAGs, comments, and non-nested variations stripped
 *  - the result token, if present, is separated from the move tokens
 *
 *  SAN legality and replay import are NOT validated at this layer.  That
 *  belongs to Stage 3.
 *
 *  @param headers    tag pairs from the PGN header section (e.g. "Event" → "?",
 *                    "White" → "Alice")
 *  @param moveTokens ordered raw SAN tokens extracted from the mainline move
 *                    text (e.g. Vector("e4", "e5", "Nf3", "Nc6"))
 *  @param result     the game result token if present:
 *                    one of "1-0", "0-1", "1/2-1/2", or "*"
 */
final case class PgnData(
  headers:    Map[String, String],
  moveTokens: Vector[String],
  result:     Option[String]
)
