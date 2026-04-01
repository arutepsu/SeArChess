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
   *  The `raw` field holds the complete FEN string; structured fields will
   *  be added when the FEN parser is implemented.
   */
  final case class ParsedFen(raw: String) extends ParsedNotation:
    def kind: ParsedNotationKind = ParsedNotationKind.Fen

  /** IR for a PGN (Portable Game Notation) document.
   *
   *  PGN consists of a header section (tag pairs) followed by a move-text
   *  section.  Both are preserved as-is at this layer.
   *
   *  @param headers   key/value tag pairs from the PGN header (e.g. "Event", "White")
   *  @param moveText  the raw move-text section including result token
   */
  final case class ParsedPgn(
    raw:      String,
    headers:  Map[String, String],
    moveText: String
  ) extends ParsedNotation:
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
