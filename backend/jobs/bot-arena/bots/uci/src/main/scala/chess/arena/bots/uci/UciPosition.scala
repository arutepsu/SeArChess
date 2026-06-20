package chess.arena.bots.uci

import chess.domain.state.GameState
import chess.notation.api.NotationFormat
import chess.notation.fen.FenNotationFacade

object UciPosition:
  def fen(state: GameState): Either[String, String] =
    FenNotationFacade
      .executeExport(state, NotationFormat.FEN)
      .map(_.text)
      .left
      .map(_.toString)
