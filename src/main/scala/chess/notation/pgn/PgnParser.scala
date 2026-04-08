package chess.notation.pgn

import chess.domain.model.{Move, PieceType, Position}
import chess.domain.state.GameState

/**
 * Minimaler PGN-Parser.
 *
 * Entfernt Kommentare und Header aus dem PGN-Text und gibt die Züge als Liste von Strings zurück.
 * Beispiel: "1. e4 e5 2. Nf3 Nc6" -> List("e4", "e5", "Nf3", "Nc6")
 * Gibt einen Fehler zurück, falls keine Züge gefunden werden.
 */
object PgnParser {
  // Minimal PGN parser: parses moves from a PGN string
  def parseMoves(pgn: String): Either[String, List[String]] = {
    // Remove tags and comments, split by whitespace
    val moveText = pgn
      .replaceAll("(?s)\\{.*?\\}", "")
      .replaceAll("\\[.*?\\]", "")
      .replaceAll("\\d+\\.", "")
      .replaceAll("\\s+", " ")
      .trim
    if (moveText.isEmpty) Left("No moves found in PGN")
    else Right(moveText.split(" ").filter(_.nonEmpty).toList)
  }
}
