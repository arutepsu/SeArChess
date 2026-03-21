package chess.application

import chess.domain.model.{Board, Color, GameStatus, Move}
import chess.domain.model.positionstate.{CastlingRights, EnPassantState}

final case class GameState(
  board:            Board,
  currentPlayer:    Color,
  moveHistory:      List[Move],
  status:           GameStatus,
  castlingRights:   CastlingRights          = CastlingRights.full,
  pendingPromotion: Option[PendingPromotion] = None,
  enPassantState:   Option[EnPassantState]  = None
)
