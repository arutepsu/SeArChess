package chess.notation.api

/** Contract for serialising a domain-side value to a notation string.
  *
  * Responsibilities:
  *   - accept a domain-side payload `A` (e.g. [[chess.domain.state.GameState]])
  *   - accept a [[NotationFormat]] describing the target serialisation format
  *   - return a structured [[ExportResult]] on success
  *   - return a structured [[NotationFailure]] on any failure
  *
  * A [[NotationExporter]] MUST NOT:
  *   - depend on or mutate domain state beyond reading `data`
  *   - parse raw input text (that is [[NotationParser]]'s responsibility)
  *   - perform semantic game-rule validation
  *
  * One implementation per supported export format. Implementations are permitted to reject
  * unsupported [[NotationFormat]] values via [[ExportFailure.UnsupportedExportFormat]].
  */
trait NotationExporter[A]:
  /** Serialise `data` to the notation text described by `format`.
    *
    * @param data
    *   the domain-side value to serialise
    * @param format
    *   the target notation format
    * @return
    *   [[Right]] containing the structured [[ExportResult]] on success, [[Left]] containing a
    *   structured [[NotationFailure]] on any failure
    */
  def exportNotation(data: A, format: NotationFormat): Either[NotationFailure, ExportResult]
