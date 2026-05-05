package chess.application.session.service

import chess.application.port.repository.{
  GameRepository,
  RepositoryError,
  SessionGameStore,
  SessionRepository
}
import chess.application.query.session.SessionView
import chess.application.session.model.SessionIds.SessionId

/** Application service for persistence-oriented session aggregate flows.
  *
  * This service exists for transport adapters that need to:
  *   - list resumable sessions
  *   - load a full persisted session + game-state aggregate
  *   - save a full persisted aggregate through the coordinated write boundary
  *   - cancel a session through the existing lifecycle service
  *
  * It intentionally does not replace authoritative gameplay command endpoints such as move
  * submission or resignation.
  */
class PersistentSessionService(
    sessionRepository: SessionRepository,
    gameRepository: GameRepository,
    store: SessionGameStore,
    sessionLifecycleService: SessionLifecycleService
):

  /** Return all non-terminal sessions as application read models. */
  def listActiveSessions(): Either[PersistentSessionError, List[SessionView]] =
    sessionRepository
      .listActive()
      .left
      .map {
        case RepositoryError.NotFound(id) =>
          PersistentSessionError.AggregateInconsistent(
            s"Unexpected not-found while listing active sessions: $id"
          )
        case RepositoryError.Conflict(message)   => PersistentSessionError.Conflict(message)
        case RepositoryError.StorageFailure(msg) => PersistentSessionError.StorageFailure(msg)
      }
      .map(_.map(SessionView.fromSession))

  /** Load the full persisted aggregate for [[sessionId]].
    *
    * If the session exists but the associated game state is missing, the aggregate is treated as
    * inconsistent and surfaced as an internal service error.
    */
  def loadAggregate(
      sessionId: SessionId
  ): Either[PersistentSessionError, PersistentSessionAggregate] =
    for
      session <- sessionRepository.load(sessionId).left.map(mapLoadSessionError(sessionId, _))
      state <- gameRepository.load(session.gameId).left.map {
        case RepositoryError.NotFound(_) =>
          PersistentSessionError.AggregateInconsistent(
            s"Session ${sessionId.value} exists but game state ${session.gameId.value} is missing"
          )
        case RepositoryError.Conflict(message)   => PersistentSessionError.Conflict(message)
        case RepositoryError.StorageFailure(msg) => PersistentSessionError.StorageFailure(msg)
      }
    yield PersistentSessionAggregate(session, state)

  /** Persist a full session aggregate through [[SessionGameStore]].
    *
    * The path [[sessionId]] must match the aggregate body's session identity. The target session is
    * required to exist before overwrite so the route can distinguish update from accidental create.
    */
  def saveAggregate(
      sessionId: SessionId,
      aggregate: PersistentSessionAggregate
  ): Either[PersistentSessionError, PersistentSessionAggregate] =
    if aggregate.session.sessionId != sessionId then
      Left(
        PersistentSessionError.BadInput(
          s"Path sessionId ${sessionId.value} does not match body sessionId ${aggregate.session.sessionId.value}"
        )
      )
    else
      for
        _ <- sessionRepository.load(sessionId).left.map(mapLoadSessionError(sessionId, _))
        _ <- store.save(aggregate.session, aggregate.state).left.map {
          case RepositoryError.Conflict(message) => PersistentSessionError.Conflict(message)
          case RepositoryError.StorageFailure(message) =>
            PersistentSessionError.StorageFailure(message)
          case RepositoryError.NotFound(_) =>
            PersistentSessionError.AggregateInconsistent(
              s"Session aggregate ${sessionId.value} disappeared during save"
            )
        }
      yield aggregate

  /** Cancel a session through the existing lifecycle boundary. */
  def cancelSession(sessionId: SessionId): Either[PersistentSessionError, SessionView] =
    sessionLifecycleService
      .cancelSession(sessionId)
      .left
      .map(mapSessionError)
      .map(SessionView.fromSession)

  private def mapLoadSessionError(
      sessionId: SessionId,
      error: RepositoryError
  ): PersistentSessionError =
    error match
      case RepositoryError.NotFound(_)         => PersistentSessionError.NotFound(sessionId)
      case RepositoryError.Conflict(message)   => PersistentSessionError.Conflict(message)
      case RepositoryError.StorageFailure(msg) => PersistentSessionError.StorageFailure(msg)

  private def mapSessionError(error: SessionError): PersistentSessionError =
    error match
      case SessionError.SessionNotFound(sessionId) => PersistentSessionError.NotFound(sessionId)
      case SessionError.GameSessionNotFound(gameId) =>
        PersistentSessionError.AggregateInconsistent(
          s"Game session for game ${gameId.value} is missing"
        )
      case SessionError.InvalidLifecycleTransition(reason) =>
        PersistentSessionError.Conflict(reason)
      case SessionError.PersistenceFailed(repoErr) =>
        repoErr match
          case RepositoryError.NotFound(id) =>
            PersistentSessionError.AggregateInconsistent(
              s"Session persistence reported missing record: $id"
            )
          case RepositoryError.Conflict(message) =>
            PersistentSessionError.Conflict(message)
          case RepositoryError.StorageFailure(message) =>
            PersistentSessionError.StorageFailure(message)
