package chess.notation.api

/** Sealed hierarchy of non-fatal observations emitted during a notation import.
  *
  * Warnings do not prevent a successful import but may indicate lossy translation, unsupported
  * extensions, or normalisation that was applied silently.
  *
  * Every variant exposes a human-readable [[message]]. Callers that need to branch on warning kind
  * pattern-match on the subtype rather than parsing the message string.
  */
sealed trait NotationWarning:
  def message: String

object NotationWarning:

  /** An unrecognised tag key was encountered and silently ignored.
    *
    * @param name
    *   the unknown tag key (e.g. a PGN tag name)
    */
  final case class UnknownTag(name: String) extends NotationWarning:
    def message: String = s"Unknown tag ignored: '$name'"

  /** A field present in the source was not mapped to the import result.
    *
    * @param field
    *   the field name
    * @param reason
    *   why it was not mapped
    */
  final case class IgnoredField(field: String, reason: String) extends NotationWarning:
    def message: String = s"Field '$field' was ignored: $reason"

  /** An extension or annotation specific to a non-standard dialect was present but is not
    * supported; it was silently dropped.
    *
    * @param extension
    *   a name or description of the dropped extension
    */
  final case class UnsupportedExtensionIgnored(extension: String) extends NotationWarning:
    def message: String = s"Unsupported extension ignored: '$extension'"

  /** The importer silently normalised part of the input to conform to a canonical form (e.g.
    * promoted a short castling annotation, filled a missing result token).
    *
    * @param description
    *   what was normalised and how
    */
  final case class NormalizationApplied(description: String) extends NotationWarning:
    def message: String = description

  /** Catch-all for warnings that do not fit a more specific category.
    *
    * Prefer using a specific subtype. Use this only when no other variant applies and introducing a
    * new variant is not warranted.
    *
    * @param message
    *   the free-form warning text
    */
  final case class GenericWarning(message: String) extends NotationWarning
