package chess.notation.api

/** Unified façade for the notation system.
 *
 *  Provides three entry points that cover the full pipeline from raw text to
 *  imported result.  Concrete implementations wire together one or more
 *  [[NotationParser]]s and [[NotationImporter]]s.
 *
 *  All methods are pure (no side effects, no mutable state).
 */
trait NotationFacade[A]:

  /** Parse `input` according to `format` and return the IR.
   *
   *  Only syntax/structural failures are possible at this stage.
   */
  def parse(
    format: NotationFormat,
    input:  String
  ): Either[ParseFailure, ParsedNotation]

  /** Map an already-parsed IR to an [[ImportResult]].
   *
   *  Semantic validation and domain mapping happen here.
   */
  def executeImport(
    parsed: ParsedNotation,
    target: ImportTarget
  ): Either[NotationFailure, ImportResult[A]]

  /** Convenience: parse and import in a single call.
   *
   *  Equivalent to `parse(format, input).flatMap(executeImport(_, target))`
   *  but may be overridden for efficiency.
   */
  def parseAndImport(
    format: NotationFormat,
    input:  String,
    target: ImportTarget
  ): Either[NotationFailure, ImportResult[A]] =
    parse(format, input).flatMap(executeImport(_, target))
