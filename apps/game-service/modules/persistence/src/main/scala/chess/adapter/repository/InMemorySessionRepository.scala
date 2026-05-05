package chess.adapter.repository

import chess.application.port.repository.{RepositoryError, SessionRepository}
import chess.application.session.model.{GameSession, SessionLifecycle}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import scala.collection.mutable

/** In-memory implementation of [[SessionRepository]].
  *
  * Intended for use in tests and as the backing store for single-process, single-JVM sessions (e.g.
  * local GUI play). Not suitable for concurrent multi-user scenarios: all operations are
  * synchronized on `this`, which prevents data corruption but does not scale.
  *
  * State is lost when the JVM exits. Replace with a durable adapter (JDBC, file-backed) when
  * persistence across restarts is required.
  */
class InMemorySessionRepository extends SessionRepository:

  private val bySessionId = mutable.HashMap.empty[SessionId, GameSession]
  private val sessionIdByGameId = mutable.HashMap.empty[GameId, SessionId]

  override def save(session: GameSession): Either[RepositoryError, Unit] =
    synchronized {
      sessionIdByGameId.get(session.gameId) match
        case Some(existingSessionId) if existingSessionId != session.sessionId =>
          Left(
            RepositoryError.Conflict(
              s"GameId ${session.gameId.value} is already owned by session $existingSessionId"
            )
          )
        case _ =>
          bySessionId.get(session.sessionId).foreach { previous =>
            if previous.gameId != session.gameId then sessionIdByGameId.remove(previous.gameId)
          }
          bySessionId.put(session.sessionId, session)
          sessionIdByGameId.put(session.gameId, session.sessionId)
          Right(())
    }

  override def load(id: SessionId): Either[RepositoryError, GameSession] =
    synchronized {
      bySessionId.get(id).toRight(RepositoryError.NotFound(id.value.toString))
    }

  override def loadByGameId(id: GameId): Either[RepositoryError, GameSession] =
    synchronized {
      sessionIdByGameId
        .get(id)
        .flatMap(bySessionId.get)
        .toRight(RepositoryError.NotFound(id.value.toString))
    }

  override def listActive(): Either[RepositoryError, List[GameSession]] =
    synchronized {
      Right(
        bySessionId.values
          .filterNot(s => isTerminal(s.lifecycle))
          .toList
      )
    }

  private def isTerminal(lifecycle: SessionLifecycle): Boolean =
    lifecycle match
      case SessionLifecycle.Finished | SessionLifecycle.Cancelled => true
      case _                                                      => false
