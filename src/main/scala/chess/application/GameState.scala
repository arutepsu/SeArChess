package chess.application

import chess.domain.model.{Board, Color, Move}

final case class GameState(
  board:         Board,
  currentPlayer: Color,
  moveHistory:   List[Move]
)
