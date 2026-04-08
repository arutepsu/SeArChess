package chess.notation.pgn

import chess.notation.api.{PgnImportResult, PgnExportResult}

/**
 * API für PGN-Import und -Export.
 *
 * Diese Datei stellt zwei Hauptfunktionen bereit:
 *
 *  - importPgn: Liest einen PGN-String ein und gibt eine Liste der Züge zurück.
 *    Nutzt intern den PgnParser.
 *
 *  - exportPgn: Wandelt eine Liste von Zügen in einen PGN-String um.
 *    Nutzt intern den PgnExporter.
 *
 * Fehler werden als Failure-Objekte zurückgegeben.
 */
object PgnNotationApi {
  /**
   * Importiert einen PGN-String und gibt die Züge als Liste zurück.
   * Nutzt den PgnParser zum Parsen.
   */
  def importPgn(pgn: String): PgnImportResult =
    PgnParser.parseMoves(pgn) match {
      case Right(moves) => PgnImportResult.Success(moves)
      case Left(reason) => PgnImportResult.Failure(reason)
    }

  /**
   * Exportiert eine Liste von Zügen als PGN-String.
   * Nutzt den PgnExporter zum Formatieren.
   */
  def exportPgn(moves: List[String]): PgnExportResult =
    try {
      val pgn = PgnExporter.exportMoves(moves)
      PgnExportResult.Success(pgn)
    } catch {
      case ex: Exception => PgnExportResult.Failure(ex.getMessage)
    }
}
