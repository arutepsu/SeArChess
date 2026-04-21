package chess.adapter.gui.notation

import chess.domain.state.GameState

/** Sealed hierarchy of outcomes returned by [[GuiNotationApi]].
  *
  * GUI callers pattern-match on these types and never need to import notation-layer ADTs
  * (NotationFailure, ImportResult, etc.).
  */
sealed trait GuiNotationOutcome

object GuiNotationOutcome:

  /** A notation import completed successfully.
    *
    * @param state
    *   the domain state produced by the import
    * @param warnings
    *   structured GUI-facing observations collected during import (empty when the import was clean)
    */
  final case class ImportSuccess(
      state: GameState,
      warnings: List[GuiNotationWarning] = Nil
  ) extends GuiNotationOutcome

  /** A notation export completed successfully.
    *
    * @param text
    *   the serialised notation string
    */
  final case class ExportSuccess(text: String) extends GuiNotationOutcome

  /** An import or export operation failed.
    *
    * @param message
    *   primary human-readable description of the problem
    * @param details
    *   optional supplementary detail (e.g. location within the input)
    * @param category
    *   coarse category enabling the UI to choose an appropriate presentation without parsing the
    *   message string
    */
  final case class Failure(
      message: String,
      details: Option[String] = None,
      category: FailureCategory = FailureCategory.InvalidInput
  ) extends GuiNotationOutcome

/** A non-fatal observation collected during a successful notation import.
  *
  * GUI callers may present, filter, or log these without inspecting notation-layer ADTs.
  *
  * @param message
  *   human-readable description of the observation
  * @param category
  *   coarse category enabling the UI to decide presentation (e.g. show a badge vs. log silently)
  */
final case class GuiNotationWarning(message: String, category: GuiWarningCategory)

/** Coarse category of a [[GuiNotationWarning]]. */
enum GuiWarningCategory:
  /** A field or tag was present but had no effect on the import result. */
  case Informational

  /** An extension or annotation was dropped; some source data was not imported. */
  case DataLoss

  /** The input was silently normalised to a canonical form. */
  case Normalization

/** Coarse category of a [[GuiNotationOutcome.Failure]], enabling GUI callers to present an
  * appropriate message without parsing the failure message string.
  */
enum FailureCategory:
  /** Raw input failed to parse: syntax, structural, or completeness problem. */
  case InvalidInput

  /** Input parsed correctly but contained semantically illegal or incompatible data. */
  case SemanticError

  /** The notation layer does not support the input dialect or version. The input may be valid in a
    * variant the system does not implement.
    */
  case UnsupportedInput

  /** The requested operation is not yet implemented in this build. */
  case UnavailableFeature
