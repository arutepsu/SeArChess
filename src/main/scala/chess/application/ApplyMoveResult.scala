package chess.application

import chess.domain.event.DomainEvent
import chess.domain.state.GameState

/** Result of applying a move to a [[GameState]] at the orchestration level.
 *
 *  @param state  the updated game state after the move
 *  @param events domain events that occurred as a result of the move, in order
 */
final case class ApplyMoveResult(
  state:  GameState,
  events: List[DomainEvent]
)
