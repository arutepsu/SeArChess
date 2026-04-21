package chess.notation.fen

import chess.domain.state.GameState
import chess.notation.api.{
  ExportFailure,
  ExportResult,
  ImportResult,
  ImportTarget,
  NotationFacade,
  NotationFailure,
  NotationFormat,
  ParsedNotation,
  ParseFailure
}

/** Concrete [[NotationFacade]] wiring [[FenParser]], [[FenImporter]], and [[FenSerializer]].
  *
  * Handles [[NotationFormat.FEN]] for both import and export. All other formats fail with a
  * structured error at the earliest applicable stage.
  *
  * Placed in `chess.notation.fen` because it composes notation-internal objects and does not belong
  * in any adapter or application layer.
  */
object FenNotationFacade extends NotationFacade[GameState]:

  def parse(format: NotationFormat, input: String): Either[ParseFailure, ParsedNotation] =
    format match
      case NotationFormat.FEN => FenParser.parse(input)
      case other => Left(ParseFailure.StructuralError(s"No parser available for format: $other"))

  def executeImport(
      parsed: ParsedNotation,
      target: ImportTarget
  ): Either[NotationFailure, ImportResult[GameState]] =
    FenImporter.importNotation(parsed, target)

  override def executeExport(
      data: GameState,
      format: NotationFormat
  ): Either[NotationFailure, ExportResult] =
    FenSerializer.exportNotation(data, format)
