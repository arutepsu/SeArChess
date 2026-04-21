package chess.notation.api

/** Sealed hierarchy of successful import results.
  *
  * Every result records:
  *   - the domain-side payload (`data`) whose type is determined by the concrete
  *     [[NotationImporter]]
  *   - the [[NotationFormat]] the data was sourced from
  *   - structured [[NotationWarning]]s — non-fatal observations that did not prevent success (e.g.
  *     unknown tags, dropped extensions)
  *
  * The subtype already encodes the [[ImportTarget]] intent:
  *   - [[PositionImportResult]] implies [[ImportTarget.PositionTarget]]
  *   - [[GameImportResult]] implies [[ImportTarget.GameTarget]]
  *
  * There is intentionally no `target` field on this trait; the subtype is the target.
  */
sealed trait ImportResult[+A]:
  def sourceFormat: NotationFormat
  def warnings: List[NotationWarning]

object ImportResult:

  /** Result of a [[ImportTarget.PositionTarget]] import.
    *
    * @param data
    *   the imported position, typed by the concrete importer
    * @param sourceFormat
    *   the [[NotationFormat]] the position was parsed from
    * @param metadata
    *   position-specific import metadata
    * @param warnings
    *   non-fatal observations collected during import
    */
  final case class PositionImportResult[A](
      data: A,
      sourceFormat: NotationFormat,
      metadata: PositionImportMetadata,
      warnings: List[NotationWarning] = Nil
  ) extends ImportResult[A]

  /** Result of a [[ImportTarget.GameTarget]] import.
    *
    * @param data
    *   the imported game, typed by the concrete importer
    * @param sourceFormat
    *   the [[NotationFormat]] the game was parsed from
    * @param metadata
    *   game-specific import metadata
    * @param replay
    *   optional replay summary describing the import characteristics; absent when not applicable or
    *   not computable
    * @param warnings
    *   non-fatal observations collected during import
    */
  final case class GameImportResult[A](
      data: A,
      sourceFormat: NotationFormat,
      metadata: GameImportMetadata,
      replay: Option[ReplaySummary] = None,
      warnings: List[NotationWarning] = Nil
  ) extends ImportResult[A]
