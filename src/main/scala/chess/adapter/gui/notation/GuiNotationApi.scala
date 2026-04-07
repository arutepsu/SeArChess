package chess.adapter.gui.notation

import chess.domain.state.GameState
import chess.notation.api.{
  CompatibilityFailure, ExportFailure, ImportFailure, ImportResult, ImportTarget,
  NotationFailure, NotationFacade, NotationFormat, NotationWarning,
  ParseFailure, ValidationFailure
}
import chess.notation.fen.FenNotationFacade
import chess.notation.pgn.PgnNotationFacade

/** GUI-facing notation API.
 *
 *  Exposes user-intention-based import and export operations without leaking
 *  notation-layer contracts to GUI callers.  All notation-layer types
 *  (NotationFormat, ParsedNotation, NotationFailure, ImportResult, …) are
 *  confined to this class's implementation; callers only see
 *  [[GuiNotationOutcome]] and its nested types.
 *
 *  Construction:
 *  - Use [[GuiNotationApi.default]] for normal application usage.
 *  - Inject a custom [[NotationFacade]] for testing.
 *
 *  Implemented now:
 *  - FEN import (delegates to the provided [[NotationFacade]])
 *
 *  Not yet implemented (returns a structured [[GuiNotationOutcome.Failure]]
 *  with [[FailureCategory.UnavailableFeature]] rather than crashing):
 *  - PGN import
 *  - FEN export
 *  - PGN export
 *
 *  @param importFacade the notation facade used for all import operations
 */
final class GuiNotationApi(importFacade: NotationFacade[GameState]):

  // ── Import ──────────────────────────────────────────────────────────────────

  /** Import a FEN string and return the corresponding [[GameState]].
   *
   *  Returns [[GuiNotationOutcome.ImportSuccess]] on success or a
   *  [[GuiNotationOutcome.Failure]] with a readable message on any error.
   */
  def importFen(text: String): GuiNotationOutcome =
    importFacade.parseAndImport(NotationFormat.FEN, text, ImportTarget.PositionTarget) match
      case Right(result) => toImportSuccess(result)
      case Left(failure) => toFailure(failure)

  /** Import a PGN string.
   *
   *  Delegates to [[PgnNotationFacade.parseAndImport]] with [[NotationFormat.PGN]] and
   *  maps the structured result to a GUI-facing outcome.
   */
  def importPgn(text: String): GuiNotationOutcome =
    PgnNotationFacade.parseAndImport(NotationFormat.PGN, text, ImportTarget.GameTarget) match
      case Right(result) => toImportSuccess(result)
      case Left(failure) => toFailure(failure)

  // ── Export ──────────────────────────────────────────────────────────────────

  /** Export the current game state as a FEN string.
   *
   *  Delegates to [[importFacade.executeExport]] with [[NotationFormat.FEN]] and
   *  maps the structured result to a GUI-facing outcome.
   */
  def exportFen(state: GameState): GuiNotationOutcome =
    importFacade.executeExport(state, NotationFormat.FEN) match
      case Right(result) => GuiNotationOutcome.ExportSuccess(result.text)
      case Left(failure) => toFailure(failure)

  /** Export the current game state as a PGN string.
   *
   *  Delegates to [[PgnNotationFacade.executeExport]] with [[NotationFormat.PGN]] and
   *  maps the structured result to a GUI-facing outcome.
   */
  def exportPgn(state: GameState): GuiNotationOutcome =
    PgnNotationFacade.executeExport(state, NotationFormat.PGN) match
      case Right(result) => GuiNotationOutcome.ExportSuccess(result.text)
      case Left(failure) => toFailure(failure)

  // ── Result extraction ────────────────────────────────────────────────────────

  private def toImportSuccess(result: ImportResult[GameState]): GuiNotationOutcome =
    result match
      case r: ImportResult.PositionImportResult[GameState @unchecked] =>
        GuiNotationOutcome.ImportSuccess(r.data, r.warnings.map(toGuiWarning))
      case r: ImportResult.GameImportResult[GameState @unchecked] =>
        GuiNotationOutcome.ImportSuccess(r.data, r.warnings.map(toGuiWarning))

  // ── Warning mapping ──────────────────────────────────────────────────────────

  /** Map a notation-layer [[NotationWarning]] to a GUI-facing [[GuiNotationWarning]].
   *
   *  Mapping rules:
   *  - [[NotationWarning.UnknownTag]]                  → [[GuiWarningCategory.Informational]]
   *  - [[NotationWarning.IgnoredField]]                → [[GuiWarningCategory.Informational]]
   *  - [[NotationWarning.UnsupportedExtensionIgnored]] → [[GuiWarningCategory.DataLoss]]
   *  - [[NotationWarning.NormalizationApplied]]        → [[GuiWarningCategory.Normalization]]
   *  - [[NotationWarning.GenericWarning]]              → [[GuiWarningCategory.Informational]]
   */
  private def toGuiWarning(w: NotationWarning): GuiNotationWarning =
    val category = w match
      case _: NotationWarning.UnknownTag                  => GuiWarningCategory.Informational
      case _: NotationWarning.IgnoredField                => GuiWarningCategory.Informational
      case _: NotationWarning.UnsupportedExtensionIgnored => GuiWarningCategory.DataLoss
      case _: NotationWarning.NormalizationApplied        => GuiWarningCategory.Normalization
      case _: NotationWarning.GenericWarning              => GuiWarningCategory.Informational
    GuiNotationWarning(w.message, category)

  // ── Failure mapping ──────────────────────────────────────────────────────────

  /** Map a notation-layer [[NotationFailure]] to a GUI-facing [[GuiNotationOutcome.Failure]].
   *
   *  Mapping rules:
   *  - [[ParseFailure]]                        → [[FailureCategory.InvalidInput]]       (syntax / structure)
   *  - [[ValidationFailure]]                   → [[FailureCategory.SemanticError]]      (semantically illegal)
   *  - [[ImportFailure]]                       → [[FailureCategory.SemanticError]]      (mapping / target mismatch)
   *  - [[ExportFailure.UnsupportedExportFormat]] → [[FailureCategory.UnavailableFeature]] (format not implemented)
   *  - [[ExportFailure.SerializationError]]    → [[FailureCategory.SemanticError]]      (domain value cannot be serialised)
   *  - [[CompatibilityFailure]]                → [[FailureCategory.UnsupportedInput]]   (dialect / version)
   */
  private def toFailure(failure: NotationFailure): GuiNotationOutcome.Failure =
    failure match
      case f: ParseFailure =>
        val details = f match
          case ParseFailure.SyntaxError(_, line, col) =>
            (line, col) match
              case (Some(l), Some(c)) => Some(s"Line $l, column $c")
              case (Some(l), None)    => Some(s"Line $l")
              case _                  => None
          case _ => None
        GuiNotationOutcome.Failure(f.message, details, FailureCategory.InvalidInput)

      case f: ValidationFailure =>
        GuiNotationOutcome.Failure(f.message, category = FailureCategory.SemanticError)

      case f: ImportFailure =>
        GuiNotationOutcome.Failure(f.message, category = FailureCategory.SemanticError)

      case f: CompatibilityFailure =>
        GuiNotationOutcome.Failure(f.message, category = FailureCategory.UnsupportedInput)

      case _: ExportFailure.UnsupportedExportFormat =>
        GuiNotationOutcome.Failure(failure.message, category = FailureCategory.UnavailableFeature)

      case _: ExportFailure.SerializationError =>
        GuiNotationOutcome.Failure(failure.message, category = FailureCategory.SemanticError)

/** Default wiring of [[GuiNotationApi]] for normal application usage.
 *
 *  Callers that need a custom facade (e.g. for testing) should instantiate
 *  [[GuiNotationApi]] directly with the desired [[NotationFacade]].
 */
object GuiNotationApi:

  /** Canonical instance backed by [[FenNotationFacade]]. */
  val default: GuiNotationApi = GuiNotationApi(FenNotationFacade)

  // ── Forwarding methods — delegates to `default` ──────────────────────────────
  // These allow GUI callers to use `GuiNotationApi.importFen(...)` directly
  // without needing to obtain the `default` instance explicitly.

  def importFen(text: String): GuiNotationOutcome       = default.importFen(text)
  def importPgn(text: String): GuiNotationOutcome       = default.importPgn(text)
  def exportFen(state: GameState): GuiNotationOutcome   = default.exportFen(state)
  def exportPgn(state: GameState): GuiNotationOutcome   = default.exportPgn(state)
