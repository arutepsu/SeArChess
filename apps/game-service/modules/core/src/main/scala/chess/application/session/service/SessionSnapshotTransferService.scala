package chess.application.session.service

import chess.application.port.repository.{RepositoryError, SessionGameStore}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import java.time.Instant

/** Application service for full session snapshot export/import.
  *
  * Export reads the existing persisted aggregate. Import writes a new aggregate through
  * [[SessionGameStore]], regenerating session and game identities by default so imported files do
  * not overwrite existing records or collide with records from another backend.
  */
class SessionSnapshotTransferService(
    persistentSessionService: PersistentSessionService,
    store: SessionGameStore
):

  import SessionSnapshotTransferError.*

  def exportSnapshot(
      sessionId: SessionId,
      exportedAt: Instant = Instant.now()
  ): Either[SessionSnapshotTransferError, SessionSnapshotEnvelope] =
    persistentSessionService
      .loadAggregate(sessionId)
      .left
      .map(mapPersistentError)
      .map(aggregate =>
        SessionSnapshotEnvelope(
          schema = SessionSnapshotTransferService.Schema,
          version = SessionSnapshotTransferService.Version,
          exportedAt = exportedAt,
          snapshot = aggregate
        )
      )

  def importSnapshot(
      envelope: SessionSnapshotEnvelope
  ): Either[SessionSnapshotTransferError, PersistentSessionAggregate] =
    for
      _ <- validate(envelope)
      imported = regenerateIds(envelope.snapshot)
      _ <- store.save(imported.session, imported.state).left.map(mapRepositoryError)
    yield imported

  private def validate(
      envelope: SessionSnapshotEnvelope
  ): Either[SessionSnapshotTransferError, Unit] =
    if envelope.schema != SessionSnapshotTransferService.Schema then
      Left(BadInput(s"Unsupported export schema: ${envelope.schema}"))
    else if envelope.version != SessionSnapshotTransferService.Version then
      Left(BadInput(s"Unsupported export version: ${envelope.version}"))
    else Right(())

  private def regenerateIds(aggregate: PersistentSessionAggregate): PersistentSessionAggregate =
    val newGameId = GameId.random()
    val newSession = aggregate.session.copy(
      sessionId = SessionId.random(),
      gameId = newGameId
    )
    aggregate.copy(
      session = newSession,
      state = aggregate.state
    )

  private def mapPersistentError(error: PersistentSessionError): SessionSnapshotTransferError =
    error match
      case PersistentSessionError.BadInput(message)   => BadInput(message)
      case PersistentSessionError.NotFound(id)        => NotFound(s"Session not found: ${id.value}")
      case PersistentSessionError.Conflict(message)   => Conflict(message)
      case PersistentSessionError.AggregateInconsistent(message) => StorageFailure(message)
      case PersistentSessionError.StorageFailure(message)        => StorageFailure(message)

  private def mapRepositoryError(error: RepositoryError): SessionSnapshotTransferError =
    error match
      case RepositoryError.NotFound(id)         => NotFound(s"Snapshot target not found: $id")
      case RepositoryError.Conflict(message)   => Conflict(message)
      case RepositoryError.StorageFailure(msg) => StorageFailure(msg)

object SessionSnapshotTransferService:
  val Schema: String = "searchess.session-export"
  val Version: Int = 1
