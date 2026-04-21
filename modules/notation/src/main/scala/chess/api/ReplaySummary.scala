package chess.notation.api

/** A structured summary of the replay characteristics of a game import.
  *
  * Concrete [[NotationImporter]] implementations for game formats (PGN, JSON game) may attach a
  * [[ReplaySummary]] to the [[ImportResult.GameImportResult]] to communicate replay facts without
  * encoding them in the domain payload.
  *
  * This type is intentionally minimal. Extend it when concrete importers need to report additional
  * replay facts.
  *
  * @param moveCount
  *   total number of half-moves (plies) in the record, if determinable at import time
  * @param isFullReplay
  *   `true` if the game record covers the complete game from start to finish; `false` for partial
  *   or truncated records
  * @param hasStartingPositionOverride
  *   `true` if the replay begins from a non-standard starting position (e.g. PGN SetUp/FEN header
  *   pair)
  */
final case class ReplaySummary(
    moveCount: Option[Int] = None,
    isFullReplay: Boolean = true,
    hasStartingPositionOverride: Boolean = false
)
