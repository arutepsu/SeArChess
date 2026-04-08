package chess.notation.api

/**
 * Ergebnis eines PGN-Exports.
 *
 * Success enthält den PGN-String.
 * Failure enthält eine Fehlermeldung.
 */
sealed trait PgnExportResult
object PgnExportResult {
  case class Success(pgn: String) extends PgnExportResult
  case class Failure(reason: String) extends PgnExportResult
}
