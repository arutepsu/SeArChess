package chess.notation.pgn

import chess.domain.model.Move
import chess.domain.state.GameState

object PgnExporter {
  // Minimal PGN exporter: converts a list of moves to a PGN string
  def exportMoves(moves: List[String]): String = {
    moves.zipWithIndex.map { case (move, idx) =>
      val moveNum = idx / 2 + 1
      if (idx % 2 == 0) s"$moveNum. $move" else move
    }.mkString(" ")
  }
}
