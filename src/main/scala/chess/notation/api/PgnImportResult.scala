package chess.notation.api

/**
 * Ergebnis eines PGN-Imports.
 *
 * Success enthält die Liste der Züge.
 * Failure enthält eine Fehlermeldung.
 */
sealed trait PgnImportResult
object PgnImportResult {
  case class Success(moves: List[String]) extends PgnImportResult
  case class Failure(reason: String) extends PgnImportResult
}
