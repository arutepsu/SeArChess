package chess.notation.api

/** The result of a successful notation export operation.
  *
  * Structured rather than a bare `String` so callers can inspect the format and any non-fatal
  * observations without parsing the text content.
  *
  * @param text
  *   the serialised notation string
  * @param format
  *   the [[NotationFormat]] the data was serialised to
  * @param warnings
  *   non-fatal observations collected during export (e.g. information that could not be represented
  *   in the target format)
  */
final case class ExportResult(
    text: String,
    format: NotationFormat,
    warnings: List[NotationWarning] = Nil
)
