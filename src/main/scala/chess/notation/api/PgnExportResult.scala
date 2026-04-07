package chess.notation.api

sealed trait PgnExportResult
object PgnExportResult {
  case class Success(pgn: String) extends PgnExportResult
  case class Failure(reason: String) extends PgnExportResult
}
