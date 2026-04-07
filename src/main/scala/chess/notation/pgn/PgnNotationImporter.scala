package chess.notation.pgn

import chess.notation.api.{ImportResult, ImportTarget}

object PgnNotationImporter {
  def importFromString(pgn: String): chess.notation.api.ImportResult[List[String]] = {
    PgnNotationApi.importPgn(pgn) match {
      case res: chess.notation.api.PgnImportResult.Success =>
        chess.notation.api.ImportResult.GameImportResult(
          data = res.moves,
          sourceFormat = chess.notation.api.NotationFormat.PGN,
          metadata = chess.notation.api.GameImportMetadata(),
          replay = Some(chess.notation.api.ReplaySummary(moveCount = Some(res.moves.length))),
          warnings = Nil
        )
      case res: chess.notation.api.PgnImportResult.Failure =>
        // Return an empty move list and a warning for failure
        chess.notation.api.ImportResult.GameImportResult(
          data = Nil,
          sourceFormat = chess.notation.api.NotationFormat.PGN,
          metadata = chess.notation.api.GameImportMetadata(),
          replay = None,
          warnings = List(chess.notation.api.NotationWarning.IgnoredField("PGN", res.reason))
        )
    }
  }
}
