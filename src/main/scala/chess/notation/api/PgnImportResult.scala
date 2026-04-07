package chess.notation.api

sealed trait PgnImportResult
object PgnImportResult {
  case class Success(moves: List[String]) extends PgnImportResult
  case class Failure(reason: String) extends PgnImportResult
}
