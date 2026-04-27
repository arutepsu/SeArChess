package chess.application.session.service

import chess.application.session.model.GameSession
import chess.domain.state.GameState

/** Application-layer aggregate used by persistence-oriented session flows.
  *
  * This boundary object groups the session metadata and its authoritative game state for load/save
  * operations without exposing transport-specific DTOs in the application layer.
  */
final case class PersistentSessionAggregate(
    session: GameSession,
    state: GameState
)
