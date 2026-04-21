package chess.notation.api

/** Metadata produced alongside a successful game import.
  *
  * Records facts about the import process that are specific to [[ImportTarget.GameTarget]] imports.
  * These fields are informational; none of them affect the `data` payload.
  *
  * @param normalized
  *   `true` if the importer silently normalised the input
  * @param sourceDialect
  *   dialect name detected or declared in the source, if any
  * @param sourceVersion
  *   format version string declared in the source, if any
  * @param hasStartingPositionOverride
  *   `true` if the game record contains a non-standard starting position (e.g. a PGN SetUp/FEN tag
  *   pair)
  */
final case class GameImportMetadata(
    normalized: Boolean = false,
    sourceDialect: Option[String] = None,
    sourceVersion: Option[String] = None,
    hasStartingPositionOverride: Boolean = false
)
