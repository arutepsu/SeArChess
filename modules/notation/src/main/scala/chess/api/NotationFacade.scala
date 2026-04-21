package chess.notation.api

/** Unified façade for the notation system.
  *
  * Provides entry points covering the full import pipeline (parse → import) and the export pipeline
  * (domain value → serialised text). Concrete implementations wire together one or more
  * [[NotationParser]]s, [[NotationImporter]]s, and [[NotationExporter]]s.
  *
  * All methods are pure (no side effects, no mutable state).
  */
trait NotationFacade[A]:

  // ── Import ──────────────────────────────────────────────────────────────────

  /** Parse `input` according to `format` and return the IR.
    *
    * Only syntax/structural failures are possible at this stage.
    */
  def parse(
      format: NotationFormat,
      input: String
  ): Either[ParseFailure, ParsedNotation]

  /** Map an already-parsed IR to an [[ImportResult]].
    *
    * Semantic validation and domain mapping happen here.
    */
  def executeImport(
      parsed: ParsedNotation,
      target: ImportTarget
  ): Either[NotationFailure, ImportResult[A]]

  /** Convenience: parse and import in a single call.
    *
    * Equivalent to `parse(format, input).flatMap(executeImport(_, target))` but may be overridden
    * for efficiency.
    */
  def parseAndImport(
      format: NotationFormat,
      input: String,
      target: ImportTarget
  ): Either[NotationFailure, ImportResult[A]] =
    parse(format, input).flatMap(executeImport(_, target))

  // ── Export ──────────────────────────────────────────────────────────────────

  /** Serialise `data` to the notation text described by `format`.
    *
    * The default implementation returns [[ExportFailure.UnsupportedExportFormat]] for every format,
    * giving implementations a safe baseline to override selectively as exporters become available.
    *
    * Concrete implementations that support a given format should override this and delegate to the
    * appropriate [[NotationExporter]].
    */
  def executeExport(
      data: A,
      format: NotationFormat
  ): Either[NotationFailure, ExportResult] =
    Left(
      ExportFailure.UnsupportedExportFormat(
        format,
        s"Export to ${format} is not yet implemented"
      )
    )
