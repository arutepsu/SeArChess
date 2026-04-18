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
case class GameHistory(
  past: List[GameState],
  current: GameState,
  future: List[GameState]
):
  def push(state: GameState): GameHistory =
    if state == current then this
    else copy(past = current :: past, current = state, future = Nil)

  def undo: Option[GameHistory] = past match
    case head :: tail => Some(copy(past = tail, current = head, future = current :: future))
    case Nil          => None

  def redo: Option[GameHistory] = future match
    case head :: tail => Some(copy(past = current :: past, current = head, future = tail))
    case Nil          => None

class InMemoryGameRepository extends GameRepository:

  private val store = mutable.HashMap.empty[GameId, GameHistory]

  override def save(gameId: GameId, state: GameState): Either[RepositoryError, Unit] =
    synchronized {
      val history = store.getOrElse(gameId, GameHistory(Nil, state, Nil))
      store.put(gameId, history.push(state))
      Right(())
    }

  override def load(gameId: GameId): Either[RepositoryError, GameState] =
    synchronized {
      store.get(gameId).map(_.current).toRight(RepositoryError.NotFound(gameId.value.toString))
    }

  override def undo(gameId: GameId): Either[RepositoryError, GameState] =
    synchronized {
      store.get(gameId) match
        case Some(history) =>
          history.undo match
            case Some(newHistory) =>
              store.put(gameId, newHistory)
              Right(newHistory.current)
            case None =>
              Left(RepositoryError.StorageFailure("No past moves to undo"))
        case None =>
          Left(RepositoryError.NotFound(gameId.value.toString))
    }

  override def redo(gameId: GameId): Either[RepositoryError, GameState] =
    synchronized {
      store.get(gameId) match
        case Some(history) =>
          history.redo match
            case Some(newHistory) =>
              store.put(gameId, newHistory)
              Right(newHistory.current)
            case None =>
              Left(RepositoryError.StorageFailure("No future moves to redo"))
        case None =>
          Left(RepositoryError.NotFound(gameId.value.toString))
    }
