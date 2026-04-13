package chess.notation.api

/** Sealed hierarchy of intermediate representations (IR) produced by parsers.
 *
 *  These are NOT domain objects.  They carry the raw or lightly-structured
 *  data extracted from a notation string.  Conversion to domain objects is
 *  the responsibility of [[NotationImporter]] implementations.
 *
 *  Each variant corresponds to one [[NotationFormat]].
 */
sealed trait ParsedNotation:
  /** The original input string that was parsed. */
  def raw: String

  /** The kind discriminator for this IR variant.
   *
   *  Useful when only the kind, not the full IR payload, needs to be reported
   *  (e.g. in [[ImportFailure.IncompatibleTarget]]).
   */
  def kind: ParsedNotationKind

object ParsedNotation:

  /** IR for a FEN (Forsyth-Edwards Notation) string.
   *
   *  FEN encodes a single board position in six space-separated fields.
   *  `raw` holds the original input string; `data` carries the structured
   *  parsed representation so importers never need to re-parse the raw text.
   */
  final case class ParsedFen(raw: String, data: FenData) extends ParsedNotation:
    def kind: ParsedNotationKind = ParsedNotationKind.Fen

  /** IR for a PGN (Portable Game Notation) document.
   *
   *  PGN consists of a header section (tag pairs) followed by a move-text
   *  section.  `data` carries the structured content extracted by the parser;
   *  `raw` preserves the original input for diagnostics.
   *
   *  @param raw   the original input string that was parsed
   *  @param data  structured PGN content: headers, move tokens, and result token
   */
  final case class ParsedPgn(raw: String, data: PgnData) extends ParsedNotation:
    def kind: ParsedNotationKind = ParsedNotationKind.Pgn

  /** IR for a JSON document representing a single board position. */
  final case class ParsedJsonPosition(raw: String) extends ParsedNotation:
    def kind: ParsedNotationKind = ParsedNotationKind.JsonPosition

  /** IR for a JSON document representing a complete game. */
  final case class ParsedJsonGame(raw: String) extends ParsedNotation:
    def kind: ParsedNotationKind = ParsedNotationKind.JsonGame

/** Discriminates between [[ParsedNotation]] variants without carrying the
 *  full IR payload.
 *
 *  Used in [[ImportFailure.IncompatibleTarget]] and wherever only the kind
 *  matters, not the data.
 */
enum ParsedNotationKind:
  case Fen
  case Pgn
  case JsonPosition
  case JsonGame
