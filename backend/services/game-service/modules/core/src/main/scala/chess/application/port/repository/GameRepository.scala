package chess.application.port.repository

import chess.application.session.model.SessionIds.GameId
import chess.domain.state.GameState

/** Outbound port for game-state persistence.
  *
  * Mirrors [[SessionRepository]] in shape and error vocabulary: all operations return
  * `Either[RepositoryError, _]`, keeping the application layer free of adapter detail.
  *
  * Implementations belong in `chess.adapter.*`; the application layer must depend only on this
  * trait, never on a concrete class.
  *
  * Interface is intentionally minimal: load by id and save (upsert) are the only access patterns
  * needed for Phase 9.
  */
trait GameRepository:

  /** Load the [[GameState]] for `gameId`.
    *
    * Returns [[RepositoryError.NotFound]] when no matching record exists.
    */
  def load(gameId: GameId): Either[RepositoryError, GameState]

  /** Persist (or replace) the [[GameState]] associated with `gameId`. */
  def save(gameId: GameId, state: GameState): Either[RepositoryError, Unit]
