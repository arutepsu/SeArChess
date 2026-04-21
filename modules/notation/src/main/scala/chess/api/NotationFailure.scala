package chess.notation.api

/** Root of the notation failure hierarchy.
  *
  * All failures carry at least a `message`. Sub-hierarchies refine the category so callers can
  * branch precisely without matching raw strings.
  *
  * Hierarchy:
  *   - [[ParseFailure]] — syntax / structural problems in the raw input
  *   - [[ValidationFailure]] — input is syntactically valid but semantically wrong
  *   - [[ImportFailure]] — parsed representation cannot be mapped to the target
  *   - [[ExportFailure]] — domain value cannot be serialised to the target format
  *   - [[CompatibilityFailure]] — format version or dialect not supported
  */
sealed trait NotationFailure:
  def message: String

// ── Parse failures ───────────────────────────────────────────────────────────

/** A failure that occurs while parsing raw text into a [[ParsedNotation]].
  *
  * Parsers return `Either[ParseFailure, ParsedNotation]` so callers that only invoke the parser see
  * a narrower error type.
  */
sealed trait ParseFailure extends NotationFailure

object ParseFailure:
  /** Input text violates the format's grammar. */
  final case class SyntaxError(
      message: String,
      line: Option[Int] = None,
      column: Option[Int] = None
  ) extends ParseFailure

  /** Input is grammatically legal but structurally incomplete or contradictory. */
  final case class StructuralError(message: String) extends ParseFailure

  /** Input ends before a required token or section is complete. */
  final case class UnexpectedEndOfInput(message: String) extends ParseFailure

// ── Validation failures ──────────────────────────────────────────────────────

/** A failure that occurs when a syntactically valid representation contains semantically invalid
  * data (e.g. an illegal position, an out-of-range value).
  */
sealed trait ValidationFailure extends NotationFailure

object ValidationFailure:
  /** A field's value is not in the set of legal values. */
  final case class InvalidValue(field: String, value: String, message: String)
      extends ValidationFailure

  /** A mandatory field is absent. */
  final case class MissingRequired(field: String, message: String) extends ValidationFailure

  /** A numeric or enumerable value falls outside the allowed range. */
  final case class OutOfRange(field: String, value: String, message: String)
      extends ValidationFailure

// ── Import failures ──────────────────────────────────────────────────────────

/** A failure that occurs when mapping a [[ParsedNotation]] to an [[ImportTarget]]. */
sealed trait ImportFailure extends NotationFailure

object ImportFailure:
  /** A field or concept in the parsed representation cannot be mapped. */
  final case class MappingError(message: String) extends ImportFailure

  /** The parsed representation is incompatible with the requested target.
    *
    * @param parsedKind
    *   the kind of [[ParsedNotation]] that was presented
    * @param target
    *   the [[ImportTarget]] that was requested
    * @param message
    *   human-readable explanation of the incompatibility
    */
  final case class IncompatibleTarget(
      parsedKind: ParsedNotationKind,
      target: ImportTarget,
      message: String
  ) extends ImportFailure

// ── Export failures ──────────────────────────────────────────────────────────

/** A failure that occurs when a domain value cannot be serialised.
  *
  * Export failures are semantically distinct from import failures: they arise from problems
  * converting a domain value *to* notation text, not from problems parsing notation text *from* an
  * external source.
  */
sealed trait ExportFailure extends NotationFailure

object ExportFailure:
  /** The domain value cannot be represented in the requested format.
    *
    * @param format
    *   the target [[NotationFormat]] that was requested
    * @param message
    *   human-readable explanation
    */
  final case class UnsupportedExportFormat(format: NotationFormat, message: String)
      extends ExportFailure

  /** A field or concept in the domain value has no counterpart in the target format.
    *
    * @param field
    *   name of the domain field or concept that could not be mapped
    * @param message
    *   human-readable explanation
    */
  final case class SerializationError(field: String, message: String) extends ExportFailure

// ── Compatibility failures ───────────────────────────────────────────────────

/** A failure indicating that the input uses a dialect or version the parser does not support.
  */
sealed trait CompatibilityFailure extends NotationFailure

object CompatibilityFailure:
  /** The input declares or implies a dialect that is not implemented. */
  final case class UnsupportedDialect(dialect: String, message: String) extends CompatibilityFailure

  /** The input declares or implies a format version that is not implemented. */
  final case class UnsupportedVersion(version: String, message: String) extends CompatibilityFailure
