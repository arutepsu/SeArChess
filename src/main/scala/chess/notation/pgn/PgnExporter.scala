package chess.notation.pgn

import chess.domain.model.Move
import chess.domain.state.GameState

/**
 * Minimaler PGN-Exporter.
 *
 * Wandelt eine Liste von Zügen in einen formatierten PGN-String um.
 * Beispiel: List("e4", "e5", "Nf3", "Nc6") -> "1. e4 e5 2. Nf3 Nc6"
 */
object PgnExporter {
  // Minimal PGN exporter: converts a list of moves to a PGN string
  def exportMoves(moves: List[String]): String = {
    moves.zipWithIndex.map { case (move, idx) =>
      val moveNum = idx / 2 + 1
      if (idx % 2 == 0) s"$moveNum. $move" else move
    }.mkString(" ")
  }
}
