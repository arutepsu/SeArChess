package chess.notation.api

/** Contract for a format-specific notation parser.
 *
 *  Responsibilities:
 *  - declare which [[NotationFormat]] it handles
 *  - convert a raw input string into a [[ParsedNotation]] IR
 *  - return a structured [[ParseFailure]] on any syntax or structural problem
 *
 *  A parser MUST NOT:
 *  - depend on or mutate domain state
 *  - emit domain events
 *  - perform semantic validation (that is [[NotationImporter]]'s responsibility)
 *
 *  One `NotationParser` implementation per supported [[NotationFormat]].
 */
trait NotationParser:
  /** The format this parser handles. */
  def format: NotationFormat

  /** Parse `input` into an intermediate representation.
   *
   *  @param input the raw notation string (must not be null)
   *  @return [[Right]] containing the IR on success,
   *          [[Left]]  containing a structured [[ParseFailure]] on any failure
   */
  def parse(input: String): Either[ParseFailure, ParsedNotation]
