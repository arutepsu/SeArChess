package chess.application.session.service

import chess.application.port.repository.RepositoryError
import chess.application.session.model.SessionIds.{GameId, SessionId}

/** Errors produced by [[SessionLifecycleService]] operations.
  *
  * Application-layer callers consume these; they never see [[RepositoryError]] directly. This keeps
  * the port error type from leaking across the service boundary.
  */
enum SessionError:
  /** No session with the given [[SessionId]] could be found. */
  case SessionNotFound(sessionId: SessionId)

  /** No session associated with the given [[GameId]] could be found. */
  case GameSessionNotFound(gameId: GameId)

  /** The repository rejected the operation with a storage-level error. */
  case PersistenceFailed(cause: RepositoryError)

  /** The requested lifecycle transition is not permitted by
    * [[chess.application.session.policy.SessionLifecyclePolicy]].
    */
  case InvalidLifecycleTransition(reason: String)
