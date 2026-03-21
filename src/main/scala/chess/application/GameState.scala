package chess.application

import chess.domain.model.{Board, Color, GameStatus, Move}

final case class GameState(
  board:            Board,
  currentPlayer:    Color,
  moveHistory:      List[Move],
  status:           GameStatus,
  pendingPromotion: Option[PendingPromotion] = None
)
