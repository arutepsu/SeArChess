package chess.notation.pgn

import chess.notation.api.ExportResult

object PgnNotationExporter {
  def exportToString(moves: List[String]): chess.notation.api.ExportResult = {
    PgnNotationApi.exportPgn(moves) match {
      case res: chess.notation.api.PgnExportResult.Success =>
        chess.notation.api.ExportResult(
          text = res.pgn,
          format = chess.notation.api.NotationFormat.PGN,
          warnings = Nil
        )
      case res: chess.notation.api.PgnExportResult.Failure =>
        chess.notation.api.ExportResult(
          text = "",
          format = chess.notation.api.NotationFormat.PGN,
          warnings = List(chess.notation.api.NotationWarning.IgnoredField("PGN", res.reason))
        )
    }
  }
}
