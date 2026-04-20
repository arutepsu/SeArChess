package chess.adapter.repository

import chess.application.port.repository.{GameRepository, RepositoryError}
import chess.application.session.model.SessionIds.GameId
import chess.domain.state.GameState
import scala.collection.mutable

/** In-memory implementation of [[GameRepository]].
 *
 *  Replaces the Phase 7 [[InMemoryGameStateStore]] with a proper adapter
 *  that satisfies the application-layer port.  Behaviour is identical:
 *  synchronized HashMap, state lost on JVM exit.
 *
 *  Single-process, single-JVM use only.  Replace with a durable adapter
 *  (JDBC, Redis, file-backed) when persistence across restarts is required.
 *
 *  Thread-safety: all operations are synchronized on `this`.
 */
class InMemoryGameRepository extends GameRepository:

  private val store = mutable.HashMap.empty[GameId, GameState]

  override def save(gameId: GameId, state: GameState): Either[RepositoryError, Unit] =
    synchronized { store.put(gameId, state); Right(()) }

  override def load(gameId: GameId): Either[RepositoryError, GameState] =
    synchronized {
      store.get(gameId).toRight(RepositoryError.NotFound(gameId.value.toString))
    }
