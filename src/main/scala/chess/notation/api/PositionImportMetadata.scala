package chess.notation.api

/** Metadata produced alongside a successful position import.
 *
 *  Records facts about the import process that are specific to
 *  [[ImportTarget.PositionTarget]] imports.  These fields are informational;
 *  none of them affect the `data` payload.
 *
 *  @param normalized     `true` if the importer silently normalised the input
 *                        (e.g. filled implicit fields, adjusted casing)
 *  @param sourceDialect  dialect name detected or declared in the source, if any
 *                        (e.g. "Chess960", "standard")
 *  @param sourceVersion  format version string declared in the source, if any
 */
final case class PositionImportMetadata(
  normalized:    Boolean        = false,
  sourceDialect: Option[String] = None,
  sourceVersion: Option[String] = None
)
