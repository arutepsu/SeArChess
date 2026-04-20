package chess.application.query.game

import chess.application.session.model.SessionIds.GameId
import chess.domain.model.{Color, Move}

/** Application read model for the legal moves available in an active game.
 *
 *  This is intentionally not a REST DTO. It belongs to the Game Service
 *  application boundary and can be mapped by any inbound adapter.
 *
 *  @param gameId        active game identifier
 *  @param currentPlayer side whose legal moves are represented
 *  @param moves         legal moves for the current player in the current state
 */
final case class LegalMovesView(
  gameId:        GameId,
  currentPlayer: Color,
  moves:         Set[Move]
)
