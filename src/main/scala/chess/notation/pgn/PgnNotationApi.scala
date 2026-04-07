package chess.notation.pgn

import chess.notation.api.{PgnImportResult, PgnExportResult}

object PgnNotationApi {
  def importPgn(pgn: String): PgnImportResult =
    PgnParser.parseMoves(pgn) match {
      case Right(moves) => PgnImportResult.Success(moves)
      case Left(reason) => PgnImportResult.Failure(reason)
    }

  def exportPgn(moves: List[String]): PgnExportResult =
    try {
      val pgn = PgnExporter.exportMoves(moves)
      PgnExportResult.Success(pgn)
    } catch {
      case ex: Exception => PgnExportResult.Failure(ex.getMessage)
    }
}
