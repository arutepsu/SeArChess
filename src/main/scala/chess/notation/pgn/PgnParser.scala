package chess.notation.pgn

import chess.domain.model.{Move, PieceType, Position}
import chess.domain.state.GameState

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
