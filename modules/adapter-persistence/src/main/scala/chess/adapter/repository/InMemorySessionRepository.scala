package chess.adapter.repository

import chess.application.port.repository.{RepositoryError, SessionRepository}
import chess.application.session.model.GameSession
import chess.application.session.model.SessionIds.{GameId, SessionId}
import scala.collection.mutable

/** In-memory implementation of [[SessionRepository]].
 *
 *  Intended for use in tests and as the backing store for single-process,
 *  single-JVM sessions (e.g. local GUI play).  Not suitable for concurrent
 *  multi-user scenarios: all operations are synchronized on `this`, which
 *  prevents data corruption but does not scale.
 *
 *  State is lost when the JVM exits.  Replace with a durable adapter (JDBC,
 *  file-backed) when persistence across restarts is required.
 */
class InMemorySessionRepository extends SessionRepository:

  private val store = mutable.HashMap.empty[SessionId, GameSession]

  override def save(session: GameSession): Either[RepositoryError, Unit] =
    synchronized {
      store.put(session.sessionId, session)
      Right(())
    }

  override def load(id: SessionId): Either[RepositoryError, GameSession] =
    synchronized {
      store.get(id).toRight(RepositoryError.NotFound(id.value.toString))
    }

  override def loadByGameId(id: GameId): Either[RepositoryError, GameSession] =
    synchronized {
      store.values
        .find(_.gameId == id)
        .toRight(RepositoryError.NotFound(id.value.toString))
    }
