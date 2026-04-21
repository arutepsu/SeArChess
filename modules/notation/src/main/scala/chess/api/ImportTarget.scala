package chess.notation.api

/** Describes the intended destination of an import operation.
  *
  * Import targets express *intent*, not behavior. The [[NotationImporter]] decides whether a given
  * [[ParsedNotation]] is compatible with the requested target and returns an [[ImportFailure]]
  * otherwise.
  */
sealed trait ImportTarget

object ImportTarget:
  /** Import a single board position (e.g. from FEN or a JSON position document). */
  case object PositionTarget extends ImportTarget

  /** Import a complete game (e.g. from PGN or a JSON game document). */
  case object GameTarget extends ImportTarget
